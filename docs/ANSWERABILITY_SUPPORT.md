# Paired answerability support experiment (v8)

This is a local **lexical query/reference classifier**, added after the frozen v7 support proposer. It can veto an acceptance but cannot select another topic, invent a source, generate an answer or upgrade a refusal. It is not a general language model or semantic entailment engine. Neither v8 policy is enabled in the application. The exact v1 default remains retained.

## What changed

`SupportPairEncoder` caches words from the 58 original bilingual FAQ records. It emits 1,024 fixed signed FNV features in four 256-dimensional blocks: query words/bigrams, character 3/4-grams, and query-word presence/absence in the reference body and title. Signed log-counts are normalized per block. No fitted vocabulary, topic-ID feature or additional proposal exemplar is introduced. Original-reference fingerprints bind each model to its proposer and document representation. Input work is bounded and immutable inference supports concurrent calls.

The extra linear binary head has **1,025 FP32 parameters (4,100 raw bytes)**. This excludes Java objects, the proposer, token dictionary, exemplar memory and reference indexes; it is not the complete model footprint. Rejected proposals skip pair encoding/head inference. `scored` distinguishes this path from an evaluated score. `withThreshold` creates another immutable, fingerprinted policy over the same weights and calibration.

## Data and training

Initial reservation commit: `11bec65c7a8a28c0e041750751d78cc1dcc836fb`. Follow-up reservation: `83686be1146e3843df851f15f88a8beab009d949`.

The new annotations are assistant-authored FAQ pairs, with a supported and unsupported question in English, Arabic and mixed text. They are not independent human labels, real traffic, unseen documents or a semantic out-of-distribution benchmark. Translations and positive/negative members stay grouped. Numeric questions are not automatically unsupported: positive examples include numbers actually supplied by a reference. No private chats or source-code corpus is used for support-model training.

| Role | Original rows | New rows | Total |
|---|---:|---:|---:|
| TRAIN, original-only comparison | 411 | 0 | 411 |
| TRAIN, contrast arm | 411 | 96 | 507 |
| DEV | 99 | 36 | 135 |
| Temperature calibration | 48 | 18 | 66 |
| Decision policy calibration | 51 | 24 | 75 |
| Initial held-out authored pairs | 0 | 72 | 72 |
| Reserved fixed-head follow-up | 0 | 48 | 48 |

Original TRAIN pair targets use whole-source-group-excluded frozen ranking. New pairs use their named FAQ reference. Only TRAIN updates weights. DEV chooses FP32 snapshots and configurations; calibration roles stay separate. The original-only and contrast arms each fit seeds 42/97 × L2 0.0001/0.001: **eight actual fits**. Sparse binary AdamW uses batch 32, learning rate 0.01, clip norm 5, decay 0.0001, at most 100 epochs and patience 12. The smoke verifies 256 finite-difference coordinates across actual positive and negative pairs, deterministic snapshots and group/role rejection.

The selected contrast fit is seed 42, L2 0.0001, epoch 23 (35 run), DEV macro-language NLL 0.510403 versus 0.562076 for the best original-only comparison. Temperature 0.75 is selected independently. On the initial 72 pairs, explicit-reference classification at probability 0.5 improves **49/72 → 62/72** and NLL **0.661915 → 0.424385**. These are pair-classifier results, not end-to-end accepted-answer accuracy.

## Original policy and reserved follow-up

The initial policy forbids losing any accepted-correct proposer example on POLICY75, minimizes accepted-wrong, then prefers the lower cutoff. It chooses **0**, so the original v8 pipeline equals v7. This negative result is preserved in its own source, selection, report and golden predictions.

After observing that outcome, a separate fixed-head protocol and 48 new questions were committed before applying or scoring an alternative. The follow-up pins the initial generated source and selection hashes. It changes only the calibration tie: prefer the **largest** cutoff with the same minimum errors and no lost correct POLICY75 examples. The same grid chooses **0.425**. No weights, temperature, features or data partition are refitted for the follow-up. The initial 72 and older 1,488 queries are now explicitly observed regressions. Their labels do not choose the alternative cutoff.

| Evaluation | Frozen v7 / initial v8 correct / wrong | Fixed-head follow-up correct / wrong | Previously correct lost |
|---|---:|---:|---:|
| Observed 1,488 | 748 / 12 | 700 / 7 | **48** |
| v7 typo/word-form stress, 120 (subset above) | 42 / 0 | 42 / 0 | 0 |
| Initial 72, now observed | 15 / 4 | 15 / 3 | 0 |
| New follow-up 48 | 18 / 0 | 18 / 0 | 0 |

**The follow-up fails recall preservation.** Reducing five old errors while discarding 48 correct acceptances is not a successful overall improvement. The new 48 provide no pipeline improvement over v7. No arm or threshold is promoted. Every lost-correct, vetoed-wrong and remaining-wrong ID is retained in `preservation-report.json`. Two plausible alternate-reference cases in the original failure audit retain their original labels; they are not silently relabeled as correct.

The veto cannot introduce a new acceptance relative to its exact proposer, but that structural property does not establish population accuracy, semantic answerability or calibrated uncertainty on arbitrary requests. Classifier scores for named pairs differ from pipeline scores when the proposer chooses another topic. At this scale, lexical collisions, related scenarios, language imbalance and false refusals remain limitations.

## Reproduce and inspect

```bash
bash scripts/train_answerability_support.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-neural-fixtures
```

JDK 17 enforces Java 8 API compatibility in CI. `--source-target-only` is an explicit local fallback when the installed JDK lacks release symbols; it does not establish the full Java 8 API check. All training sources also compile without checkpoint classes; experiment checkpoints are loaded reflectively. No Android dependency is needed for training or main-runtime compilation.

Committed exports live in `neural-core/checkpoints/answerability-support-v8`. Reproduction compares both policies, the matched arms and all golden predictions, including the original no-veto outcome. The reload smoke checks 1,734 original predictions, 3,468 matched-arm predictions, 1,782 follow-up predictions, 1,782 retained-default checks and 35,640 original reference checks. The standalone host/ART contract uses the exact packaged follow-up, typed flags, score tolerances, frozen ranks and original reference content, plus corrupt-fixture rejection. API 30 CI writes a current-run `answerability-correctness.json`; configuring that test is not evidence it has passed.

`host-timings.json` records interleaved descriptive end-to-end host measurements against v7. It imposes no speed gate and is not a phone benchmark or heap-footprint measurement. A physical Infinix X689 test and independent labels remain outstanding. Future quality work should first address the recorded false refusals with genuinely separate review/evaluation before collecting more claims of improvement.
