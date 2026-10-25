#include "llm_core.h"
#include "llama.h"
#include "ggml-backend.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <mutex>

namespace flyllm {

struct Engine {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int nCtx = 0;
    std::mutex mu;
    std::string stats;
};

std::string loadBackends(const std::string& libDir) {
    static std::string desc;
    static std::once_flag once;
    std::call_once(once, [&] {
        if (!libDir.empty()) ggml_backend_load_all_from_path(libDir.c_str());
        for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
            ggml_backend_reg_t reg = ggml_backend_reg_get(i);
            desc += ggml_backend_reg_name(reg);
            auto feats = (ggml_backend_get_features_t) ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features");
            if (feats) for (auto* f = feats(reg); f && f->name; f++) { desc += " "; desc += f->name; }
            desc += "; ";
        }
        if (desc.empty()) desc = "no backend loaded";
    });
    return desc;
}

std::string stats(Engine* e) { std::lock_guard<std::mutex> lock(e->mu); return e->stats; }

static std::once_flag backendOnce;

Engine* load(const std::string& path, int nCtx, int nThreads, std::string& err) {
    std::call_once(backendOnce, [] { llama_backend_init(); });
    if (ggml_backend_dev_count() == 0) { err = "no CPU backend available (call loadBackends first)"; return nullptr; }
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap = true;  // weights paged from flash on demand: lower resident RAM
    llama_model* model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { err = "failed to load model (corrupt or unsupported GGUF)"; return nullptr; }
    auto cp = llama_context_default_params();
    cp.n_ctx = nCtx; cp.n_batch = 512; cp.n_threads = nThreads; cp.n_threads_batch = nThreads;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); err = "failed to create context (out of memory?)"; return nullptr; }
    auto* e = new Engine; e->model = model; e->ctx = ctx; e->vocab = llama_model_get_vocab(model); e->nCtx = nCtx;
    return e;
}

std::string chat(Engine* e, const std::vector<std::string>& roles, const std::vector<std::string>& contents,
                 int maxTokens, const std::function<bool(const std::string&)>& onPiece, std::string& err) {
    std::lock_guard<std::mutex> lock(e->mu);
    std::vector<llama_chat_message> msgs;
    for (size_t i = 0; i < roles.size(); i++) msgs.push_back({roles[i].c_str(), contents[i].c_str()});
    const char* tmpl = llama_model_chat_template(e->model, nullptr);
    std::vector<char> buf(8192);
    int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int)buf.size());
    if (n > (int)buf.size()) { buf.resize(n); n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int)buf.size()); }
    if (n < 0) { err = "chat template not supported by this model"; return ""; }
    std::string prompt(buf.data(), n);

    int nTok = -llama_tokenize(e->vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(nTok);
    if (llama_tokenize(e->vocab, prompt.c_str(), (int)prompt.size(), toks.data(), nTok, true, true) < 0) { err = "tokenize failed"; return ""; }
    int budget = e->nCtx - maxTokens - 8;
    if (budget < 64) { err = "context too small"; return ""; }
    if ((int)toks.size() > budget) toks.erase(toks.begin(), toks.end() - budget);  // keep the most recent part of the chat

    using clk = std::chrono::steady_clock;
    auto t0 = clk::now();
    llama_memory_clear(llama_get_memory(e->ctx), true);
    for (size_t i = 0; i < toks.size(); i += 512) {
        int cnt = (int)std::min<size_t>(512, toks.size() - i);
        if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, cnt)) != 0) { err = "decode failed"; return ""; }
    }
    auto sp = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sp);
    // Qwen3 non-thinking recommended sampling: top_k 20, top_p 0.8, temp 0.7, presence penalty 1.5 (quantized models
    // otherwise loop and repeat); fresh random seed each reply
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.8f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.0f, 0.0f, 1.5f));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto t1 = clk::now();
    int generated = 0;
    std::string out; char piece[256];
    for (int i = 0; i < maxTokens; i++, generated++) {
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        int len = llama_token_to_piece(e->vocab, t, piece, sizeof(piece), 0, false);
        if (len > 0) { std::string s(piece, len); out += s; if (onPiece && !onPiece(s)) break; }
        if (llama_decode(e->ctx, llama_batch_get_one(&t, 1)) != 0) { err = "decode failed"; break; }
    }
    llama_sampler_free(smpl);
    double pt = std::chrono::duration<double>(t1 - t0).count(), gt = std::chrono::duration<double>(clk::now() - t1).count();
    char sbuf[160];
    std::snprintf(sbuf, sizeof sbuf, "prompt %zu tok in %.1f s (%.0f tok/s), generated %d tok at %.1f tok/s",
                  toks.size(), pt, toks.size() / std::max(pt, 1e-3), generated, generated / std::max(gt, 1e-3));
    e->stats = sbuf;
    return out;
}

void release(Engine* e) {
    if (!e) return;
    llama_free(e->ctx); llama_model_free(e->model); delete e;
}
}  // namespace flyllm
