// Minimal chat wrapper over llama.cpp (MIT). Runs GGUF models such as Qwen3-0.6B fully on device.
#pragma once
#include <functional>
#include <string>
#include <vector>

namespace flyllm {
struct Engine;
Engine* load(const std::string& path, int nCtx, int nThreads, std::string& err);
// roles: "system"/"user"/"assistant". Uses the model's own chat template. onPiece may return false to stop.
std::string chat(Engine* e, const std::vector<std::string>& roles, const std::vector<std::string>& contents,
                 int maxTokens, const std::function<bool(const std::string&)>& onPiece, std::string& err);
void release(Engine* e);
}
