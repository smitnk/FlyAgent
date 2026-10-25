#include "connectome.h"
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>
#include <random>

namespace fly {

namespace {
template <class T> bool rd(const uint8_t*& p, const uint8_t* end, T* out, size_t n) {
    size_t bytes = sizeof(T) * n;
    if ((size_t)(end - p) < bytes) return false;
    std::memcpy(out, p, bytes); p += bytes; return true;
}
}

bool loadBuffer(const uint8_t* data, size_t len, Connectome& c, std::string& err) {
    const uint8_t* p = data; const uint8_t* end = data + len;
    char magic[4]; uint32_t ver, n, srcLen; uint64_t e;
    if (!rd(p, end, magic, 4) || std::memcmp(magic, "FLYC", 4) != 0) { err = "not a FLYC connectome file"; return false; }
    if (!rd(p, end, &ver, 1) || ver != 1) { err = "unsupported version"; return false; }
    if (!rd(p, end, &n, 1) || !rd(p, end, &e, 1) || !rd(p, end, &srcLen, 1) || srcLen > 4096) { err = "truncated header"; return false; }
    c.source.resize(srcLen);
    if (!rd(p, end, c.source.data(), srcLen)) { err = "truncated source"; return false; }
    c.ids.resize(n); c.rowptr.resize((size_t)n + 1); c.post.resize(e); c.w.resize(e);
    if (!rd(p, end, c.ids.data(), n) || !rd(p, end, c.rowptr.data(), (size_t)n + 1) ||
        !rd(p, end, c.post.data(), e) || !rd(p, end, c.w.data(), e)) { err = "truncated body (corrupt or incomplete file)"; return false; }
    // integrity checks: CSR must be monotonic and all targets in range
    if (c.rowptr[0] != 0 || c.rowptr[n] != e) { err = "corrupt CSR offsets"; return false; }
    for (uint32_t i = 0; i < n; i++) if (c.rowptr[i] > c.rowptr[i + 1]) { err = "corrupt CSR order"; return false; }
    for (uint64_t k = 0; k < e; k++) if (c.post[k] >= n || !std::isfinite(c.w[k])) { err = "corrupt edge data"; return false; }
    return true;
}

bool load(const std::string& path, Connectome& c, std::string& err) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) { err = "cannot open " + path; return false; }
    std::vector<uint8_t> buf((size_t)f.tellg());
    f.seekg(0); f.read((char*)buf.data(), buf.size());
    return loadBuffer(buf.data(), buf.size(), c, err);
}

Result simulate(const Connectome& c, const std::vector<uint32_t>& excite, double rateHz,
                const std::vector<uint32_t>& silence, double durationMs, int trials, uint64_t seed,
                const Params& P, const volatile bool* cancel) {
    auto t0 = std::chrono::steady_clock::now();
    const uint32_t N = c.n();
    const int steps = (int)std::lround(durationMs / P.dt_ms);
    const int D = (int)std::lround(P.t_dly / P.dt_ms);           // 18 steps
    const int RF = (int)std::lround(P.t_rfc / P.dt_ms);          // 22 steps
    // exact solution of the linear system dv/dt=(v0-v+g)/t_mbr, dg/dt=-g/tau over one dt (Brian2 'linear' method)
    const double em = std::exp(-P.dt_ms / P.t_mbr), es = std::exp(-P.dt_ms / P.tau);
    const double kx = P.tau / (P.tau - P.t_mbr) * (es - em);
    const double pPoi = rateHz * P.dt_ms / 1000.0, poiJump = P.w_syn * P.f_poi;

    std::vector<uint8_t> silenced(N, 0), driven(N, 0);
    for (auto i : silence) if (i < N) silenced[i] = 1;
    for (auto i : excite) if (i < N) driven[i] = 1;

    Result R; R.spikes.assign(N, 0);
    std::vector<double> u(N), g(N);                  // u = v - v0
    std::vector<int> refUntil(N);
    std::vector<uint8_t> inActive(N);
    std::vector<uint32_t> active, nextActive, spiking;
    std::vector<std::vector<std::pair<uint32_t, float>>> ring(D + 1);  // delayed synaptic events
    std::mt19937_64 rng(seed);
    std::uniform_real_distribution<double> uni(0, 1);

    for (int tr = 0; tr < trials; tr++) {
        std::fill(u.begin(), u.end(), 0); std::fill(g.begin(), g.end(), 0);
        std::fill(refUntil.begin(), refUntil.end(), -1); std::fill(inActive.begin(), inActive.end(), 0);
        for (auto& r : ring) r.clear();
        active.clear();
        auto touch = [&](uint32_t i) { if (!inActive[i]) { inActive[i] = 1; active.push_back(i); } };

        for (int s = 0; s < steps; s++) {
            if (cancel && *cancel) return R;
            // 1) state update (only neurons away from rest: event-driven speedup, exact for resting neurons)
            spiking.clear(); nextActive.clear();
            for (uint32_t i : active) {
                if (s >= refUntil[i]) { double gi = g[i]; u[i] = u[i] * em + gi * kx; g[i] = gi * es; }
                // 2) threshold
                if (s >= refUntil[i] && u[i] + P.v0 > P.v_th) spiking.push_back(i);
            }
            // 3) synapses: Poisson drive onto v, delayed events onto g
            for (auto i : excite) if (i < N && uni(rng) < pPoi) { u[i] += poiJump; touch(i); }
            auto& due = ring[s % (D + 1)];
            for (auto& ev : due) { g[ev.first] += ev.second; touch(ev.first); }
            due.clear();
            // 4) reset + emit spikes into the delay ring
            for (uint32_t i : spiking) {
                u[i] = P.v_rst - P.v0; g[i] = 0;
                refUntil[i] = s + 1 + (driven[i] ? 0 : RF);   // Poisson targets have no refractory period (as in the paper)
                R.spikes[i]++; R.totalSpikes++;
                if (silenced[i]) continue;   // silencing removes all its synapses
                auto& slot = ring[(s + D) % (D + 1)];
                for (uint32_t k = c.rowptr[i]; k < c.rowptr[i + 1]; k++) {
                    uint32_t j = c.post[k];
                    if (!silenced[j]) slot.emplace_back(j, (float)(c.w[k] * P.w_syn));
                }
            }
            // drop neurons that have relaxed back to rest
            for (uint32_t i : active) {
                if (std::fabs(u[i]) < 1e-6 && std::fabs(g[i]) < 1e-6 && s >= refUntil[i]) { u[i] = 0; g[i] = 0; inActive[i] = 0; }
                else nextActive.push_back(i);
            }
            active.swap(nextActive);
        }
    }
    R.wallMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return R;
}

}  // namespace fly
