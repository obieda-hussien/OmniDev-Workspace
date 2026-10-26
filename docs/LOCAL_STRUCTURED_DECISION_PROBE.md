# Local structured decision probe

Status: experimental, opt-in, diagnostic only. This is not part of the production AUTO router.

## Why this uses the existing local engine

Workspace already has a GGUF loader and a JNI inference path through `llama.cpp`, plus the deterministic `IntentClassifier` and the bounded local `ModeDecisionModel`. The probe reuses a user-selected model that is already loaded; it does not download or bundle a model, add MediaPipe, or start inference during normal chat.

Jev is a hosted TypeSafe decision API, not downloadable on-device weights. It can inspire a typed decision contract, but a locally prompted LLM is not the same model or an equivalent accuracy guarantee:
- TypeSafe API access: https://systemonemodels.org/guides/how-to-get-jev-access/
- SmolLM2-135M-Instruct model card: https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct
- MediaPipe mobile deployment and model packaging: https://ai.google.dev/gemma/docs/integrations/mobile and https://ai.google.dev/gemma/docs/conversions/hf-to-mediapipe-task
- XGrammar constrained decoding: https://github.com/mlc-ai/xgrammar

MediaPipe LLM Inference uses compatible LiteRT/Task Bundle assets; an arbitrary GGUF file is not a drop-in `model.bin` for that API. Since Workspace already uses GGUF via llama.cpp, adding a second mobile inference stack is deferred until benchmarks demonstrate a concrete advantage.

## What the probe measures

For one manually entered request, it compares:
1. Omni's existing local heuristic mode.
2. A candidate from the already-loaded GGUF model.
3. Strict JSON validity, model file size, available system RAM before the call, process PSS before/peak/delta, and elapsed time.

Accepted output must be one JSON object with exactly these keys:
- `schema_version`: integer `1`
- `mode`: `CHAT`, `AGENT`, or `SWARM`
- `needs_code`, `needs_device`, `needs_research`, `parallelizable`: JSON booleans
- `reason`: non-empty string, at most 160 characters

Stringified booleans/numbers, unknown keys, duplicate object keys, nested objects in scalar fields, surrounding prose, malformed JSON, and oversized reasons are rejected. A prompt and temperature of zero do **not** guarantee valid JSON or a semantically correct decision; invalid output is discarded rather than repaired heuristically.

## Safety and resource limits

The current probe rejects before inference if:
- The input exceeds 2,048 characters or the stricter context-derived character budget.
- The active GGUF context size or model file size is unknown.
- The active context cannot fit the reserved fixed prompt, the minimum 48-token output budget, and at least one input character.
- The selected model file exceeds 400 MiB.
- Available system RAM is below 384 MiB.
- Current process PSS is already at or above 400 MiB.


The probe reads the active native context size from the engine. It reserves 320 tokens for fixed instructions and chooses an output budget between 48 and 96 tokens, capped by the context size. Remaining context is converted conservatively into a character limit, also capped at 2,048 characters. This is an admission heuristic—not an exact tokenizer measurement—so overflow still results in a rejected candidate, never a routing decision.

During inference it asks for at most 96 new tokens, times out the collection after 15 seconds, and samples process PSS every 100 ms. If a sample reaches 400 MiB, cancellation is requested and the candidate is discarded.

**These are safeguards, not a hard memory guarantee.** PSS sampling can miss a brief spike between samples, and JNI generation shutdown depends on the native loop responding to its stop signal. An inference already in native code can take time to return during cancellation. The model's file size is also not its runtime RAM footprint: the mapped weights, KV cache, working buffers and the rest of the Android app all contribute. Do not claim a 400 MiB runtime ceiling until device measurements substantiate it.

The input, raw model output and reason are not persisted or logged by the probe. The candidate is diagnostic-only: it cannot call tools, grant permissions, approve actions, or override production routing. It is not consulted by AUTO.

## Evaluation plan before any routing integration

Use the intended device (initial target: Infinix X689 / Android 11 / Helio G85) and a fixed held-out dataset with Arabic, English and code-switched requests. Include clear chat, single-step execution, multi-domain independent work, ambiguous requests, prompt-injection strings, and requests requiring device/research/code capabilities.

Report separately:
- Strict-schema acceptance rate (not semantic accuracy).
- Agreement with the baseline router and adjudicated intent accuracy.
- False SWARM rate and missed independent-work rate.
- Median/p95 latency, tokens generated, peak PSS, and failures/OOM/cancellation.
- Cold model load cost separately from warm inference.

Do not promote the model into production AUTO merely because it emits valid JSON or agrees with the heuristic. Require a meaningful held-out accuracy gain without unacceptable latency/memory regressions, and preserve user-controlled mode switching and all existing permission/tool gates.
