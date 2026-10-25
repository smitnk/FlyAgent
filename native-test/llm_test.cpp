// Host smoke test with the REAL Qwen3-0.6B GGUF (downloaded in CI): llm_test <model.gguf>
#include "../app/src/main/cpp/llm_core.h"
#include <cassert>
#include <chrono>
#include <cstdio>

int main(int argc, char** argv) {
    std::string err;
    auto t0 = std::chrono::steady_clock::now();
    auto* e = flyllm::load(argv[1], 2048, 4, err);
    if (!e) { std::fprintf(stderr, "load: %s\n", err.c_str()); return 1; }
    auto out = flyllm::chat(e, {"system", "user"}, {"You are a concise assistant.", "What is the capital of France? Answer in one word. /no_think"}, 64, nullptr, err);
    double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("reply: [%s]\nerr: [%s] total %.1fs\n", out.c_str(), err.c_str(), s);
    std::printf("::notice::Qwen3 host test reply: %s\n", out.substr(out.find("</think>") == std::string::npos ? 0 : out.find("</think>") + 8, 80).c_str());
    assert(out.find("Paris") != std::string::npos);
    flyllm::release(e);
    std::printf("llm_test OK\n");
}
