# Third-party data, models and code

| Component | Source | License | Where it is used |
|---|---|---|---|
| FlyWire adult *Drosophila* connectome v783 | FlyWire Consortium, [doi:10.5281/zenodo.10676866](https://doi.org/10.5281/zenodo.10676866); Dorkenwald et al. 2024, Schlegel et al. 2024 (Nature) | CC BY 4.0 | Connectivity only. Subset bundled in the APK; full brain published as release `connectome-v783` |
| Connectivity export + LIF model parameters | Shiu et al. 2024, [philshiu/Drosophila_brain_model](https://github.com/philshiu/Drosophila_brain_model) @ `91bdd1e` | MIT | `Connectivity_783.parquet`/`Completeness_783.csv` converted by `scripts/build_connectome.py`; parameters re-implemented in `app/src/main/cpp/connectome.cpp`; their Brian2 output used for validation |
| llama.cpp `b6999` | [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT | On-device LLM runtime (fetched at build time) |
| Qwen3-0.6B (GGUF Q4_0 / Q8_0) | [Qwen/Qwen3-0.6B](https://huggingface.co/Qwen/Qwen3-0.6B), GGUF by [ggml-org/Qwen3-0.6B-GGUF](https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF) | Apache-2.0 | Downloaded by the user inside the app (not in the APK); SHA-256 checked |
| ML Kit text recognition / image labeling | Google | ML Kit terms | Offline image reading |

Connectivity is measured data. Neural activity in FlyAgent is a **model simulation** (leaky integrate-and-fire), not a recording,
and coupling the simulator to an LLM does not create biological intelligence.
