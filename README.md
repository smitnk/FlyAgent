# FlyAgent

Lightweight self-learning Android AI agent, inspired by the fruit-fly brain ([bio-agi](https://github.com/shepherdscientific/bio-agi), MIT).

**Status: Milestone 1** — agent loop, persistent on-device memory, web search, page reading, file creation, offline fallback.

## Get the APK
Every push runs GitHub Actions (`.github/workflows/build.yml`): unit tests + `assembleDebug`.
Download **FlyAgent-debug-apk** from the run's *Artifacts* section.

## Features (v0.2)
- **Modes** (dropdown): `OFFLINE` (no model, no internet needed), `CLOUD` (API only), `HYBRID` (router decides, falls back to on-device).
- **APIs with on/off switches** (APIs button): Claude, ChatGPT, NVIDIA NIM, Custom (OpenRouter/Groq/Ollama). Tried top to bottom with failover. Keys stay in app-private storage.
- **Image reading**: attach an image. Cloud models see it directly; offline uses on-device ML Kit OCR + labels.
- **Bio-AGI fly router** (switch): port of [bio-agi](https://github.com/shepherdscientific/bio-agi)'s routing — 6 LIF neuron populations → activation vector → urgency scaling → module skip. Off = conventional controller. Memory screen shows success rate per controller (A/B).
- **Learning**: 👍 turns the last task's steps into a versioned skill, tested before being marked reliable, reused later (even offline). 👎 saves your correction; it's used in future prompts.
- **Safety barrier (in code, not just prompt)**: blocks illegal/harmful requests (hacking, malware, weapons, drugs, fraud, piracy, stalking), workspace-only files, no local-network or file:// access, no executable downloads, unknown tools refused, card numbers/IDs/passwords redacted before cloud, no phone permissions except internet. Every action is logged to memory and results are labelled AI-generated.

## Tests
`gradle test` runs offline, cloud-only and hybrid mode tests with fake models, plus safety, router and skill tests (`app/src/test`).

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
