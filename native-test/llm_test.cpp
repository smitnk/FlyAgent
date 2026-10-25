// Host smoke test with the REAL Qwen3-0.6B GGUF (downloaded in CI): llm_test <model.gguf>
#include "../app/src/main/cpp/llm_core.h"
#include <cstdlib>
#include <chrono>
#include <cstdio>

int main(int argc, char** argv) {
    std::string err;
    auto t0 = std::chrono::steady_clock::now();
    std::printf("::notice::CPU backend: %s\n", flyllm::loadBackends(argc > 2 ? argv[2] : "").c_str());
    auto* e = flyllm::load(argv[1], 2048, 4, err);
    if (!e) { std::fprintf(stderr, "load: %s\n", err.c_str()); return 1; }
    auto out = flyllm::chat(e, {"system", "user"}, {"You are a concise assistant.", "What is the capital of France? Answer in one word. /no_think"}, 64, nullptr, err);
    double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("::notice::LLM speed: %s\n", flyllm::stats(e).c_str());
    std::printf("reply: [%s]\nerr: [%s] total %.1fs\n", out.c_str(), err.c_str(), s);
    std::string shown = out.substr(out.find("</think>") == std::string::npos ? 0 : out.find("</think>") + 8);
    for (auto& ch : shown) if (ch == '\n') ch = ' ';
    std::printf("::notice::Qwen3 host test reply: %s (%.1fs incl. load)\n", shown.c_str(), s);
    if (out.find("Paris") == std::string::npos) { std::fprintf(stderr, "expected Paris\n"); return 1; }
    flyllm::release(e);
    std::printf("llm_test OK\n");
}
