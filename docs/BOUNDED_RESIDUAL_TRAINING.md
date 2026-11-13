# Bounded residual training: preserve the teacher, reject ineffective adaptation

This experiment tests a correction to the frozen proposal model instead of replacing it with the six-class model that regressed earlier known cases. It did **not** demonstrate better decision quality. The selected gain is zero, the adapter remains inactive, and `production_eligible` is false. Runtime/export/ART parity checks are separate from this negative quality result.

## Protocol and data

`neural-core/fixtures/residual-adapter/protocol.json` was versioned before fitting. Use the prior authored/bilingual corpus and pinned public MASSIVE subset, preserving all existing partitions, scenario groups and parallel translation IDs. Only supported training rows fit the residual: 886 unique rows, balanced to 3,900 examples across 15 language/domain strata. Unsupported training rows do not receive invented domain labels.

Before fitting, reserve additional unused **official test** utterance groups from the same checksum-pinned MASSIVE 1.1 Arabic/English source. Whole translation pairs are excluded on old source-ID or normalized-text overlap. The remaining reservation has 160 utterances (80 semantic groups), all unsupported under the existing narrow experimental contract: 40 each music playback, sending email, calendar-set and weather. There are **no remaining new supported positives** in this reservation. It cannot establish new Arabic known-domain accuracy, new topic coverage, Egyptian dialect quality or standard MASSIVE benchmark results. Reuse the CC BY 4.0 attribution files already retained with the public corpus. No source worker IDs, private chats or model reasoning are stored.

## Model and fit

Freeze the complete rich-v1 plus conservative public-refusal-v1 cascade as teacher. Keep its vocabulary/IDF and original calibrated five-domain probabilities. A zero-initialized 1024x5 tanh layer supplies bounded per-logit residuals, 5,125 parameters. Compute a corrected distribution as:

`softmax(log(max(teacher_probability, 1e-30)) + gain * residual)`

Gain stays in [0,1], and every residual must stay in [-1,1]. No prior abstention can become a choice. Gain zero returns the exact prior Decision object and skips residual encoding/inference. Active adaptation may propose a different domain or abstain; this is not the earlier refusal-only contract and grants no permission or execution authority. The active path currently re-encodes surviving choices; no active speedup is claimed. The normal APK has no dependency on these experiments.

Train six candidates: anchor coefficients 1/4/16 and seeds 42/97. The objective is supervised cross-entropy on known labels plus a frozen-teacher KL anchor on old training examples, with weight decay .005. SGD checks development macro NLL every five epochs, retains copied best weights and stops after 20 epochs without improvement, within 100 epochs. Select the candidate by development NLL; only calibration chooses the maximum gain from .125/.25/.5/1. Development considers gains up to that maximum using fixed .6 probability/.15 margin, requires no previously correct known decision to be lost, maximizes language-macro correctness and prefers smaller gains on ties. Tests never select the weights or policy.

Selected anchor16, seed97, epoch35 (55 epochs run), development macro NLL .548555 and calibration maximum gain .5. The exact development policy results explain why a lower loss is insufficient:

| Gain | Correct known development choices | Prior correct choices lost | Language-macro correctness |
|---|---:|---:|---:|
| 0 | 65 | 0 | .640016 |
| .125 | 65 | 0 | .640016 |
| .25 | 65 | 0 | .640016 |
| .5 | 64 | 1 | .627195 |

The policy therefore selects **gain0**. Small corrections do not improve decisions; the larger eligible correction loses a correct case. Do not enable one simply because its likelihood improved. The versioned report explicitly records `adapter_active:false` and `quality_improvement_demonstrated:false`.

## Observations after export

Export weights and the selected policy before reading/scoring the fresh reservation. Original/public prior test sets remain observed regressions; only the extra 160 utterances were newly reserved for this experiment. Because gain is zero, every selected decision matches the teacher:

| Set | Correct known choices | Unsupported refused |
|---|---:|---:|
| Prior public Arabic test | 14/52 | 89/228 |
| Prior public English test | 20/52 | 97/228 |
| Original authored regression | 25/30 | — |
| Same-domain compositions | 30/30 | — |
| Cross-domain compositions | — | 44/120 |
| Newly reserved unsupported utterances | No positives | 69/160 |

There is no quality gain or regression from the selected inactive adapter. Fresh refusal is still weak: 91/160 unsupported utterances are accepted by the teacher. The old proposal/rejection model needs better representative data and evaluated changes; more epochs alone do not prove progress. No P0/P2/P5 completion checks are relaxed.

One warmed, rotated host timing run measured teacher ~20.04us/text and inactive wrapper ~20.07us/text over 192 texts per batch, 64 each Arabic/English/mixed, eight raw batches. This measures the **inactive** path, not active residual inference. It is not a latency improvement, p95, peak-heap, cold-start, Android or physical-phone benchmark. If all weights were loaded, the complete cascade has 19,618 parameters, excluding vocabulary/object overhead; no extra model enters normal application builds.

## Verification and reproduction

```bash
python3 scripts/reserve_residual_eval.py --verify
python3 scripts/test_residual_data.py
bash scripts/train_residual_adapter.sh
bash scripts/prepare_neural_android_fixtures.sh build/residual-android-fixtures
```

The default is strict JDK release8 compilation. The explicitly named `--source-target-only` fallback is weaker and used locally on this host. `--verify-artifacts` checks existing fit outputs without another fit. Full training exports a chunked Java checkpoint; numeric residuals and decision probabilities are compared against compiled exports and committed weights within 2e-6 across 4,090 observations. Metadata, exact typed outcomes, exact dense/sparse inference and preservation of prior refusals are checked. Reports identify teacher/checkpoint, corpus and protocol hashes. The trainer control checks real learnability, deterministic fits, copied best snapshots, bounded outputs and group leakage using the actual optimizer.

There are six additional runtime JUnit tests, eight reservation/provenance tests and continued older regression tests. The package-only distribution factory validates finite normalized probabilities, bounded unique labels, thresholds and copied results; existing TinyDecisionModel.decide behavior is unchanged.

The standalone Android test APK additionally packages the residual checkpoint, never a normal APK dependency. It retains the existing 139/699 probes and adds 859 residual probes (699 existing inputs plus 160 reserved utterances). Compare feature hashes, FP32 residuals, five probabilities, typed choices and dense/sparse results for both selected gain0 and a **forced gain1 numerical-control path**. Forced activation is a test only and is not a promoted candidate. Prior refusals and selected zero-gain outcomes must remain exactly unchanged. Three more corrupt-fixture cases reject altered hashes, missing rows and forged statuses. Fresh run-ID plus residual-parity/count fields are mandatory in the Android correctness report.

Locally passed using source/target8: six candidate fits and export checks, 4,090-observation compiled/committed parity, 59 real runtime JUnit tests, the actual trainer control, eight new data guards, prior public/bilingual data tests, host Android fixtures including selected/forced-active residuals and ten corrupt-fixture rejections. New strict release8, Gradle and ART verification is delegated to the PR CI; it must be inspected independently. No physical Infinix test was performed.

## Next data requirement

Do not tune this now-observed reservation into a claimed fresh benchmark. The next training milestone needs additional independently reserved **supported** Arabic/English groups, especially Android and developer tasks, plus separate unsupported and mixed-intent groups. Evaluate individual correct-case retention, coverage and rejection together before any integration. Preserve the zero-gain result as a reproducible negative control when comparing the next candidate.
