// Runs the engine on a real connectome file: sim_cli <file.bin> <ids.txt> <hz> <trials> <out.json>
#include "../app/src/main/cpp/connectome.h"
#include <cstdio>
#include <fstream>
#include <unordered_map>

int main(int argc, char** argv) {
    if (argc < 6) { std::fprintf(stderr, "usage: sim_cli file ids hz trials out\n"); return 2; }
    fly::Connectome c; std::string err;
    if (!fly::load(argv[1], c, err)) { std::fprintf(stderr, "load failed: %s\n", err.c_str()); return 1; }
    std::unordered_map<int64_t, uint32_t> idx; for (uint32_t i = 0; i < c.n(); i++) idx[c.ids[i]] = i;
    std::vector<uint32_t> exc; std::ifstream in(argv[2]); long long id;
    while (in >> id) if (idx.count(id)) exc.push_back(idx[id]);
    if (exc.empty()) { std::fprintf(stderr, "none of the stimulus IDs are in this connectome\n"); return 1; }
    int trials = std::atoi(argv[4]);
    auto r = fly::simulate(c, exc, std::atof(argv[3]), {}, 1000, trials, 1234);
    std::FILE* o = std::fopen(argv[5], "w"); std::fprintf(o, "{");
    bool first = true;
    for (uint32_t i = 0; i < c.n(); i++) if (r.spikes[i]) { std::fprintf(o, "%s\"%lld\":%.3f", first ? "" : ",", (long long)c.ids[i], r.spikes[i] / (double)trials); first = false; }
    std::fprintf(o, "}\n"); std::fclose(o);
    std::printf("%s\nneurons=%u edges=%llu stimulated=%zu spikes=%llu wall=%.0f ms (%d trials x 1 s)\n",
        c.source.c_str(), c.n(), (unsigned long long)c.e(), exc.size(), (unsigned long long)r.totalSpikes, r.wallMs, trials);
}
