// Connectome LIF engine. Data: real FlyWire v783 connectivity (via Shiu et al. 2024 export).
// Dynamics: MODELED — leaky integrate-and-fire with the published parameters of Shiu et al.
// (github.com/philshiu/Drosophila_brain_model, model.py). The connectivity is measured; the dynamics are a model.
#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace fly {

struct Connectome {
    std::string source;              // provenance string stored in the file
    std::vector<int64_t> ids;        // FlyWire root IDs, index = neuron index
    std::vector<uint32_t> rowptr;    // CSR over presynaptic neuron
    std::vector<uint32_t> post;      // postsynaptic neuron index
    std::vector<float> w;            // signed synapse count (Excitatory x Connectivity)
    uint32_t n() const { return (uint32_t)ids.size(); }
    uint64_t e() const { return post.size(); }
};

// File format "FLYC" v1 (little endian):
// char[4] "FLYC" | u32 version=1 | u32 N | u64 E | u32 srcLen | char[srcLen] source
// | i64 ids[N] | u32 rowptr[N+1] | u32 post[E] | f32 w[E]
bool load(const std::string& path, Connectome& c, std::string& err);
bool loadBuffer(const uint8_t* data, size_t len, Connectome& c, std::string& err);

struct Params {               // defaults = Shiu et al. default_params
    double dt_ms = 0.1;
    double v0 = -52, v_rst = -52, v_th = -45;   // mV
    double t_mbr = 20, tau = 5;                 // ms
    double t_rfc = 2.2, t_dly = 1.8;            // ms
    double w_syn = 0.275;                       // mV per synapse
    double f_poi = 250;                         // Poisson synapse scale
};

struct Result {
    std::vector<uint32_t> spikes;  // total spikes per neuron, summed over trials
    uint64_t totalSpikes = 0;
    double wallMs = 0;
};

// excite: neuron indices driven by independent Poisson input at rateHz (as optogenetic activation in the paper)
// silence: neuron indices whose synapses are removed (to and from)
Result simulate(const Connectome& c, const std::vector<uint32_t>& excite, double rateHz,
                const std::vector<uint32_t>& silence, double durationMs, int trials, uint64_t seed,
                const Params& p = Params(), const volatile bool* cancel = nullptr);

}  // namespace fly
