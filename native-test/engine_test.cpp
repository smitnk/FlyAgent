// Host-side tests for the connectome engine (run in CI with g++). Uses a hand-made 3-neuron FIXTURE, not fly data.
#include "../app/src/main/cpp/connectome.h"
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <vector>

// CHECK stays active in Release builds (CHECK() is compiled out with NDEBUG)
#define CHECK(x) do { if (!(x)) { std::fprintf(stderr, "CHECK failed line %d: %s\n", __LINE__, #x); std::exit(1); } } while (0)

static std::vector<uint8_t> makeFile(std::vector<int64_t> ids, std::vector<uint32_t> rowptr, std::vector<uint32_t> post, std::vector<float> w) {
    std::vector<uint8_t> b; auto put = [&](const void* p, size_t n) { auto c = (const uint8_t*)p; b.insert(b.end(), c, c + n); };
    uint32_t ver = 1, n = ids.size(); uint64_t e = post.size(); std::string src = "test fixture";
    uint32_t sl = src.size();
    put("FLYC", 4); put(&ver, 4); put(&n, 4); put(&e, 8); put(&sl, 4); put(src.data(), sl);
    put(ids.data(), 8 * n); put(rowptr.data(), 4 * (n + 1)); put(post.data(), 4 * e); put(w.data(), 4 * e);
    return b;
}

int main() {
    // A -> B (strong excitatory, 200 synapses), A -> C (strong inhibitory), nothing drives C
    auto f = makeFile({11, 22, 33}, {0, 2, 2, 2}, {1, 2}, {200.f, -200.f});
    fly::Connectome c; std::string err;
    CHECK(fly::loadBuffer(f.data(), f.size(), c, err));
    CHECK(c.n() == 3 && c.e() == 2 && c.source == "test fixture");

    // no input -> no activity at all
    auto r0 = fly::simulate(c, {}, 100, {}, 1000, 2, 1);
    CHECK(r0.totalSpikes == 0);

    // drive A at 100 Hz: A fires ~100/s, B follows, C (inhibited) stays silent
    auto r = fly::simulate(c, {0}, 100, {}, 1000, 5, 42);
    double a = r.spikes[0] / 5.0, b = r.spikes[1] / 5.0;
    std::printf("A=%.1f Hz B=%.1f Hz C=%u\n", a, b, r.spikes[2]);
    CHECK(a > 70 && a < 130);
    CHECK(b > 10);
    CHECK(r.spikes[2] == 0);

    // silencing A removes its synapses -> B silent
    auto rs = fly::simulate(c, {0}, 100, {0}, 1000, 5, 42);
    CHECK(rs.spikes[1] == 0);

    // determinism with same seed
    auto r2 = fly::simulate(c, {0}, 100, {}, 1000, 5, 42);
    CHECK(r2.spikes == r.spikes);

    // corruption detection: truncated file and out-of-range target
    fly::Connectome bad;
    CHECK(!fly::loadBuffer(f.data(), f.size() - 3, bad, err));
    auto g = makeFile({11, 22}, {0, 1, 1}, {7}, {1.f});
    CHECK(!fly::loadBuffer(g.data(), g.size(), bad, err));
    std::printf("engine_test OK\n");
}
