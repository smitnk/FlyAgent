# FlyAgent

Lightweight self-learning Android AI agent, inspired by the fruit-fly brain ([bio-agi](https://github.com/shepherdscientific/bio-agi), MIT).

**Status: Milestone 1** — agent loop, persistent on-device memory, web search, page reading, file creation, offline fallback.

## Get the APK
Every push runs GitHub Actions (`.github/workflows/build.yml`): unit tests + `assembleDebug`.
Download **FlyAgent-debug-apk** from the run's *Artifacts* section.

## v0.4: real fly connectome + offline LLM
- **Fruit-fly brain engine (C++ / JNI):** loads the real FlyWire v783 wiring (138,639 neurons, 15,091,983 connections, 54.5 M synapses) and simulates activity with the leaky integrate-and-fire model of Shiu et al. 2024. A 6,000-neuron sugar-taste → feeding subset ships in the APK; the full brain (47 MB download) is fetched in the app from the `connectome-v783` release.
- **Validated in CI** against the paper's own Brian2 run (sugar GRN activation at 100 Hz): full brain Pearson r = 0.83 with published rates, 39/50 top neurons shared, MN9 73.4 Hz vs 67.0 Hz.
- **Offline LLM:** Qwen3-0.6B (Apache-2.0) via llama.cpp, downloaded once from Hugging Face and SHA-256 verified. CI runs the real model through the same C++ wrapper.
- **Integration:** the LLM plans and talks; it can call `brain_sim` as a tool; results are stored in memory and labelled *real connectivity / modeled activity*.
- Cloud APIs (Claude, ChatGPT, NVIDIA, custom), image reading, skills/feedback learning and the safety barrier are unchanged.

## Try it
- Type `brain sugar` (or ⋮ → Fly brain → Run sugar → feeding test).
- `brain stim <FlyWire IDs> [Hz]`, `brain silence <ID>` (silences a neuron during sugar stimulation), `brain info`.
- ⋮ → Local LLM → Download → Qwen3-0.6B Q4_0 (429 MB, Wi-Fi), then switch Mode to Offline and chat.

See [NOTICE.md](NOTICE.md) for data/model licenses and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
