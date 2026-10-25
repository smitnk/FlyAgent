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
