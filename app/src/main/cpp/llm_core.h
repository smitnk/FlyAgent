// Minimal chat wrapper over llama.cpp (MIT). Runs GGUF models such as Qwen3-0.6B fully on device.
#pragma once
#include <functional>
#include <string>
#include <vector>

namespace flyllm {
struct Engine;
// Loads the best CPU backend variant for this phone (ARMv8.0 / v8.2 dotprod / fp16 / v8.6 i8mm) from libDir.
// Returns a description of the selected CPU features. Call once before load().
std::string loadBackends(const std::string& libDir);
Engine* load(const std::string& path, int nCtx, int nThreads, std::string& err);
// "prompt N tok in X s, generated M tok at Y tok/s" for the last chat() call
std::string stats(Engine* e);
// roles: "system"/"user"/"assistant". Uses the model's own chat template. onPiece may return false to stop.
std::string chat(Engine* e, const std::vector<std::string>& roles, const std::vector<std::string>& contents,
                 int maxTokens, const std::function<bool(const std::string&)>& onPiece, std::string& err);
void release(Engine* e);
}
