# Practical on-device voice research for Omni

Reviewed on 14 October 2026. Main search window: 14 October 2025 to 14 October 2026 (365 days). Four Exa searches returned 32 results (31 unique URLs before entity-level merging) across ASR, keyword spotting and TTS; current author documentation and original papers were fetched to verify the selected claims. Historical baselines are included only for context. This document proposes experiments; it does not claim that these engines were integrated or benchmarked on the Infinix X689.

## Recommendation

Evaluate a compact streaming Arabic ASR engine before adding a large universal speech model. Keep wake detection independent of general transcription, use text plus acoustic enrollment for custom phrases, and measure mixed-language recognition separately from monolingual WER. Keep installed offline Android TTS as the baseline while measuring any replacement. On a roughly 4 GB Android 11 phone, model residency, native allocations, endpointing and audio handoff can matter as much as parameter count.

## Evidence and deployment implications

| Development | Reported result / capability | Reliability and qualification | Value for Omni |
|---|---|---|---|
| [Moonshine v2, February 2026](https://arxiv.org/abs/2602.12241) | Sliding-window encoder attention bounds streaming work; authors report accuracy comparable to much larger models with lower latency. | Primary paper; released implementation. Results do not establish latency on this phone. | Incremental transcripts and cached encoder state rather than repeatedly decoding the whole growing recording. |
| [Current Moonshine Arabic Tiny Streaming](https://moonshine-voice.readthedocs.io/en/stable/models/available-models/) | 34M parameters, MIT, author-reported Arabic WER 15.5%. | [Accuracy documentation](https://moonshine-voice.readthedocs.io/en/stable/models/accuracy/) says non-English checks use seeded 400-clip samples. These are not leaderboard submissions, Egyptian-dialect tests, or Arabic-English mixed-command evaluations. Older Arabic Community models have different licenses and evaluation sets. | Most promising small Arabic candidate to benchmark against Vosk. Pin the exact checkpoint/runtime/license; do not inherit older-model benchmark claims. |
| [Qwen3-ASR, January 2026](https://github.com/QwenLM/Qwen3-ASR) | 0.6B/1.7B families; 30 languages including Arabic plus 22 Chinese dialects; streaming/offline inference and language identification. | Original repository, released weights. “52” combines languages and dialects. Advertised high batch throughput is not old-phone latency; mixed Arabic-English still needs its own test. | Stronger optional reference/teacher or higher-device-tier candidate, not the always-listening default. |
| [Omnilingual ASR, November 2025](https://arxiv.org/abs/2511.09690) | Broad recognition coverage across 1600+ languages and extensibility to additional languages. | Primary research with released ecosystem. Breadth does not establish equal accuracy, arbitrary language-pair mixing, or low-end-phone real-time performance. | A direction for language-pack coverage and optional broader tiers; no “every language perfectly” claim. |
| [DMA-KWS, May 2026](https://arxiv.org/abs/2605.22120) | Streaming phoneme candidate search plus fine verification, text/audio enrollment, and adaptation of 187k parameters. Authors report 97.85% AUC / 6.13% EER on LibriPhrase Hard. | Original paper lists TASLP acceptance; [author repository](https://github.com/aizhiqi-work/DMA-KWS) still has older under-review wording and says pretrained checkpoints are coming. EER is not false activations per listening hour; ready Android weights are not established. | Best architectural inspiration for user-defined phrases and confusable negatives. Treat as a design/training project, not a ready library replacement. |
| [ZP-KWS, revised September 2026](https://arxiv.org/html/2606.20106v2) | Keyword content plus a compact speaker branch; reported 1.55M budget and up to 60% relative reduction in target-only FRR at 1% FAR. | Primary preprint and code link. Absolute target-only FRR remains 29.47% on LibriPhrase Easy and 78.20% on Hard at that strict point. This is not a replay-resistant authentication result or an Arabic deployment test. | Optional speaker preference can reduce other-speaker activation; hard gating needs careful tuning because it can miss the owner's phrase. Never use wake matching to unlock the phone. |
| [MATE, January 2026 / ICASSP 2026](https://arxiv.org/abs/2601.14012) | Nested audio/text embedding prefixes with PCA-guided alignment; improved WSJ/LibriPhrase retrieval without extra inference overhead. | Primary accepted paper. Its reported inference uses the full embedding; smaller prefixes do not by themselves prove a cheaper encoder or Arabic streaming support. | Research option for matching several enrolled phrases efficiently. A coarse/full verification scheme is an Omni experiment, not a reported outcome. |
| [Pocket TTS, January 2026 and later releases](https://kyutai.org/pocket-tts/) / [current repository](https://github.com/kyutai-labs/pocket-tts) | 100M generator, continuous audio latents, streamed audio; current repository lists seven languages and about 200 ms first chunk / 6x real-time on MacBook Air M4. | Open code and weights. Arabic is absent from the current supported-language list. Laptop CPU results and streamed output do not establish token-by-token input streaming or Android latency. | Interesting future English/other-language option and synthesis architecture; not the immediate Arabic replacement. |
| [Supertonic 3, April 2026](https://github.com/supertone-oss-archive/supertonic) | 99M, ONNX, 31 languages including Arabic and a language-agnostic mode. | Official code/weights, but repository is now archived and development/support have ended. Code is MIT; weights use OpenRAIL-M. Author reading-accuracy metrics are not phone latency or a guarantee for Egyptian pronunciation. | An Arabic-capable experimental TTS comparator, with a maintenance burden and pinned assets; keep a supported fallback. Supertonic 2's five-language list did not include Arabic. |

Scores above use different corpora, languages and protocols. Do not rank engines by comparing these numbers directly. Author papers/model cards substantiate what was measured, not independent replication of Omni's deployment.

## What mixed-language recognition requires

Language switching between sentences can sometimes be routed using language identification and natural speech boundaries. Switching inside one sentence, especially app names embedded in Egyptian Arabic, has no dependable pause at the switch. VAD-only routing can discard or misdecode the foreign span.

A [June 2026 study on unseen code-switching pairs](https://arxiv.org/abs/2606.05846) found only modest transfer from bilingual fine-tuning/model merging, testing English, Korean, Japanese and German. It did not demonstrate Egyptian Arabic-English. Multilingual coverage and within-sentence switching must be evaluated independently.

For Omni, test one jointly multilingual decoder or a small bilingual student as the primary mixed-command path. Contextual vocabulary should include authorized app names such as Omni, Settings and Shizuku, with pronunciation variants where supported. Preserve foreign words in transcription; let the intent layer resolve app identities from verified installed-app metadata. Do not silently rewrite an uncertain name into an action. Confidence/uncertainty should cause clarification for consequential actions.

Synthetic mixed-language training audio can expand coverage, but real recordings must remain the held-out test. Training examples cannot be reused as validation clips. Text-to-phoneme conversion must cover Arabic pronunciation variants; English G2P alone is insufficient. A multilingual label on a model is not evidence that every language pair works.

## Proposed audio architecture

1. A single AudioRecord owner feeds bounded 16 kHz mono PCM buffers. Roughly two seconds of 16-bit pre-roll is about 64 KB. Buffers stay in memory and are cleared when capture stops or consent changes.
2. A cheap speech/activity gate and a small streaming keyword detector run before loading general ASR. Cache enrollment text features and streaming acoustic state.
3. A candidate wake span receives a second, finer phrase check. Optional speaker matching uses separately calibrated settings. Thresholds are tuned using real confusable words/noise, not the wake phrase itself as a negative.
4. Open the assistant immediately after a verified wake. Transfer buffered post-wake audio to ASR so a contiguous wake-plus-command is not lost while the UI/model starts. This extends the existing single-recorder ownership policy; it does not mean opening a second microphone concurrently.
5. Streaming ASR emits a rolling hypothesis, then stable text. Adaptive endpointing reacts to conversational pauses and decoder stability. The intent layer resolves the action and checks flavor permissions/consent.
6. TTS speaks stable clauses while later text is prepared, using a short cancellable queue. User interruption stops queued speech and resumes command capture where allowed. Echo handling prevents Omni's own speaker output from repeatedly waking it.
7. A resource manager avoids keeping every language/ASR/TTS model resident alongside a local LLM. Compare one/two CPU threads, quantization, cold/warm starts, temperature and peak native RSS. Hardware DSP wake remains a manufacturer integration, not a capability created by sideloading an OEM APK.

This is a proposed architecture. In the current code, software wake segments audio before matching an enrolled feature template; ordinary dictation uses Vosk, and speech output uses installed offline Android TTS voices. New neural backends and the buffered wake-to-command handoff are not part of the runtime-stability fix.

## Experiment worth implementing: Omni Voice Lab

Build an opt-in, development-only native benchmark harness with WAV replay plus actual microphone/locked-screen soak tests. Record exact model hash, quantization, engine version, ABI, thread count and device. Preserve the existing voice backend while experiments run behind explicit model selection.

### Phase A: ASR first, using available weights

Compare the current Vosk Arabic baseline, current MIT Arabic Moonshine Tiny Streaming, and a [quantized multilingual Whisper Tiny](https://github.com/ggml-org/whisper.cpp) baseline. Use the same clips and final-text normalization. Add Qwen3-ASR only as an optional stronger reference; measure it rather than assuming it fits the low-end budget. This phase is immediately actionable because released ASR weights exist, unlike DMA-KWS's pending pretrained checkpoints.

Use about 200 held-out commands covering Arabic-only, English-only, and Egyptian Arabic with embedded English app names. Include continuous wake-plus-command recordings, distances, fan/TV noise and ordinary disfluencies. Separate enrollment clips, speakers and calibration data from evaluation. Report per-subset WER/CER, exact app-name recall and semantic command success. Keep intended meaning separate from transcript spelling conventions.

### Phase B: wake verification

Compare the existing enrolled-template detector against a lightweight two-stage prototype. Evaluate Hi Omni and two genuinely different custom phrases, using text plus acoustic enrollment. Include closely sounding negatives, other speakers, replayed audio and changing noise. Use explicit user-confirmed corrections for adaptation; never self-label every detected wake as true.

Measure false accepts per hour and false rejects per positive utterance separately. Paper AUC/EER/FAR percentages cannot be converted directly into listening-hour claims. Calibrate before the held-out test; do not tune thresholds on the test negatives.

### Phase C: spoken response

Keep offline Android TTS as the fixed baseline. Compare an Arabic-capable experimental neural backend only after measuring its RAM and latency. Evaluate Arabic numbers, abbreviations, Latin app names inside Arabic, repeated/skipped words, and stopping speech. Pocket TTS's current language set does not qualify as an Arabic comparator. Supertonic 3 qualifies for experimentation but requires owning maintenance of its archived runtime/assets.

### Proposed acceptance targets, not measured results

| Metric | Initial target |
|---|---|
| Wake recognition | At least 95% quiet / 90% noisy held-out owner utterances |
| Wake UI latency | P95 under 300 ms after the phrase ends; report cold/warm separately |
| ASR speed | Real-time factor below 1 on the actual phone, without rising latency during a long session |
| Mixed-command quality | At least 20% relative improvement in mixed-command error versus the existing baseline, with no meaningful monolingual regression |
| First audible response | P95 under 700 ms after a stable clause is available; exclude model download time, report loading separately |
| Voice memory | An initial budget around 250 MB incremental peak RSS, adjusted to measured available memory and local-LLM coexistence |
| Reliability | No app ANR/native crash during an overnight listening/interaction soak; measured battery and temperature, with full stop/revocation tests |

For a false-wake target around 0.1/hour, zero false accepts in 30 hours gives an approximate 95% Poisson upper bound of 3/30 = 0.1/hour. This assumes comparable independent conditions; it does not prove behavior in every room or background distribution. A short quiet demonstration is insufficient evidence of reliable always-on wake detection.

Select the smallest candidate that passes the measured task-quality and latency gates. The first production improvement should be streaming transcription and preserving the command during wake handoff; adoption of larger neural TTS or speaker gating follows evidence from the phone. No benchmark result for this experiment is claimed yet.
