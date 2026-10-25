# FlyAgent architecture

## What bio-agi actually is (studied first)
- Python, MIT licence. Layers: `router/` (FlyWire connectome, leaky-integrate-and-fire simulation → per-population spike rates → activation vector), `cognitive/` (memory, world model, goal, reward, emotion, executive, critic modules — each an LLM call), `inference/` (OpenAI-compatible / Anthropic / BitNet backends), `orchestrator/`.
- Memory: SQLite + cosine similarity. Embedding is a hash placeholder.
- It has **no tool execution** yet; the connectome only decides which modules run, LLMs do all reasoning.
- The full 138k-neuron simulation is not realistic on a phone and adds no language ability.

**Decision:** port the *routing idea*, not the simulation. Milestone 3 adds a `Controller` interface with two implementations — a conventional controller and a small "fly-inspired" router (activation weights per module, emotion/urgency scaling, threshold skip, critic gate) — so acceptance test 12 can compare them on the same tasks.

## Module map
| Spec module | Where | Milestone |
|---|---|---|
| Cognitive controller | `Agent` loop (conventional); `FlyRouter` later | 1 / 3 |
| Goal manager | LLM step loop, max 8 steps | 1 → structured plans in 2 |
| Memory retrieval | `MemoryDb.recall` (keyword overlap) | 1 → FTS/embeddings in 4 |
| World/task state | episode steps log | 1 → task table + resume in 6 |
| Inference gateway | `LlmClient` (any OpenAI-compatible URL) + offline rules | 1 → local model in 5 |
| Tool executor | `Tools` | 1 |
| Evaluator | success flag per episode | 1 → verification criteria in 2 |
| Learning system | — | 2 (skills table, versioned, tested before "reliable") |
| Memory compressor | — | 4 (non-destructive; originals kept, sha256 checked) |
| Safety controller | workspace-only files, name whitelist, http(s)-only fetch, tool output marked untrusted | 1 → approval prompts in 2 |

## Memory layers (target)
- **A Essential:** preferences, active goals (SharedPreferences / small table).
- **B Semantic:** `facts` table with source, confidence, timestamp (created in M1).
- **C Episodic:** `episodes` table (M1); summarised + deduplicated in M4, originals retained.
- **D Procedural:** `skills` table — name, inputs, steps, verification tests, version, status (M2).
- **E Exact records:** files in workspace + sha256; gzip for archives (M4).

## Roadmap
1. ✅ Agent loop, SQLite memory, search, fetch, files, offline fallback, CI APK.
2. Skills + feedback learning (👍/👎 and corrections on each result; save validated procedures).
3. Controller interface + fly-inspired router, A/B logging.
4. Consolidation/compression, hash verification, corruption detection tests.
5. Optional downloadable quantized local model (not in base APK).
6. Drive backup, WorkManager task recovery, controlled browser.

## v0.4 — real connectome + local LLM (replaces the 6-population router)
```
UI (chat) ─► Agent ─► Safety ─► reasoning: cloud LLM | Qwen3-0.6B (llama.cpp, JNI) | rules
                       │
                       ├─ Tools: web, files, recall, brain_sim ─► BrainLab ─► ConnectomeBrain (JNI) ─► C++ LIF engine
                       └─ Memory (SQLite): episodes, facts, skills
```
- `app/src/main/cpp/connectome.cpp`: CSR connectome, exact linear integration (dt 0.1 ms), 1.8 ms synaptic delay ring,
  2.2 ms refractory, Poisson drive (w_syn·f_poi), event-driven updates (only neurons away from rest are integrated).
  File integrity is checked on load (CSR monotonic, targets in range, finite weights).
- Data pipeline: `scripts/build_connectome.py` (CI) → `fly783_subset.bin` (asset) + `fly783_full.bin.gz` (release, .sha256).
- Validation: `native-test/sim_cli` + `scripts/validate_sim.py` fail CI if the full-brain result diverges from the paper (r ≤ 0.8 or MN9 silent).
  Reference is the paper's v630 run; 1 of the 21 sugar GRN IDs no longer exists in v783 and is reported, not replaced.
- Resources: model and connectome are loaded lazily and freed on `onTrimMemory(BACKGROUND)`. LLM uses mmap, 2–4 threads, 2048-token context.
