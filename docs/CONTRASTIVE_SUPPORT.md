# Balanced contrastive support experiment (v10)

V9's positive-cost objective preserved correct acceptances but reduced balanced pair classification. V10 tests unweighted binary cross-entropy with same-scenario contrastive ranking, comparing a linear head with a tiny nonlinear head. It improves explicit-reference classification on the new authored challenge; **the selected model does not improve final acceptance counts over v9**. Both outcomes are retained. No experimental model is enabled in the application.

Reservation commit: `937de8b8261ace830fd1343760789a91814c0a65`. All hyperparameters, selection rules and 96 new questions were frozen before fitting/scoring. All 19 v9 CI jobs, including API 30 ART, passed on its exact prior head `2b3696305cdbd9fb7981c4e732363592c393724a`.

## Representation and training

Keep the v7 proposer, v8 lexical pair encoder, 58 original bilingual FAQ records, TRAIN 507, DEV 135, TEMP 66 and POLICY 75 unchanged. Original TRAIN targets still use whole-source-group-excluded frozen ranking. Of the TRAIN rows, 96 contrast annotations supply exactly **48 supported/unsupported pairs**, each sharing a group and language. No DEV, calibration or evaluation pair supplies gradients. Translations and opposite polarity remain grouped.

Eight actual fits vary:

| Dimension | Reserved values |
|---|---|
| Head | Linear 1,024→1; or 1,024→8 tanh→1 |
| Parameters | 1,025; or 8,209 |
| Pairwise loss weight | 0; or 0.25 |
| Seed | 42; or 97 |

The objective is unweighted BCE plus the optional mean `softplus(-(positive_logit - negative_logit))` ranking loss and L2 0.001. AdamW uses 32 BCE rows and 16 TRAIN pairs per update, learning rate 0.003, norm cap 5, decay 0.0001, at most 100 epochs and patience 12. Both row and pair orders shuffle each epoch, including the zero-ranking controls. Sparse double-precision backpropagation trains the unrounded network; snapshot selection and deployment use the portable FP32 runtime, including intermediate FP32 activations.

Unweighted macro-language DEV NLL chooses snapshots and configurations. Ties prefer fewer parameters, zero ranking weight and seed 42. The selected head is **tanh8, ranking weight 0.25, seed 42, epoch 10/22**, DEV NLL **0.501275**. Matched linear ranking reaches **0.528242** at epoch 100/100; nonlinear BCE reaches **0.509300** at epoch 12/24. These are matched-budget comparisons: the linear fits reach the fixed budget, so this is not proof they could never improve with another separately reserved budget.

TEMP 66 selects temperature **0.75**. The same v9 engineering guard of 783 fitting/development/calibration rows chooses cutoff **0.1**, preserving all 512 full-proposer accepted-correct guard rows, then minimizing errors on POLICY 75 and preferring the largest allowed grid cutoff. A policy-only control chooses **0.5**. The guard includes fitted data and TRAIN exemplars; it is not an unbiased risk bound, conformal guarantee or out-of-fold estimate.

Calibration caches the immutable scores needed for the grid rather than rerunning inference for every cutoff. A naive-grid reference test verifies the chosen cutoff. Source and golden output equality also holds against the uncached calculation. The immutable `SupportContrastiveModel` constructor fixes the exact permitted topology, clones and validates every parameter, binds proposer/encoder/head/calibration fingerprints and supports concurrent inference. Rejected proposals explicitly remain unscored. The model only vetoes: it cannot change topic order, rewrite original references, generate an answer or upgrade a refusal.

The selected head contains **8,209 FP32 parameters, 32,836 raw bytes**. This excludes the proposer, Java objects, reference/exemplar indexes and the exported comparison heads. It is not the total application footprint or a phone memory measurement. No larger network or ensemble is loaded into the production pipeline.

## New 96-question challenge

The challenge consists of 16 assistant-authored supported/unsupported FAQ pairs in English, Arabic and mixed text. These are known topics and related scenarios, not independently reviewed human questions, user traffic, unseen documents or semantic out-of-distribution validation. Every method below scores the same 96 named query/reference pairs; this differs from scoring the document actually selected by the proposer.

| Named-reference method | Correct at 0.5 / 96 | Unweighted NLL | Brier | Correct positive-before-negative / 48 |
|---|---:|---:|---:|---:|
| v9 selected positive-weighted | 49 | 0.613288 | 0.219956 | 45 |
| v9 unit-weight comparison | 82 | 0.353764 | 0.107819 | 44 |
| v10 matched linear ranking | 82 | 0.377068 | 0.117457 | 46 |
| v10 matched nonlinear BCE | 81 | 0.353989 | 0.108865 | 45 |
| **v10 selected nonlinear ranking** | **84** | **0.343791** | **0.103364** | **46** |

This recovers balanced classification relative to v9's weighted head and gives a small improvement over the stronger unit-weight comparison. Pairwise ranking helps the matched nonlinear control on this targeted sample. The model and arm selection used DEV, not the fresh results.

## Acceptance counts and remaining failures

| Evaluation | Frozen proposer correct / wrong | Selected v10 correct / wrong | Selected v9 correct / wrong |
|---|---:|---:|---:|
| Observed 1,872 | 870 / 31 | 870 / 29 | 870 / 29 |
| Original 1,488, subset above | 748 / 12 | 748 / 12 | 748 / 12 |
| New 96 | 30 / 6 | 30 / 6 | 30 / 6 |
| v7 typo/word-form 120, subset of observed | 42 / 0 | 42 / 0 | 42 / 0 |

The selected model loses no previously correct proposer acceptance in these evaluations, but the new pipeline gains **zero** over v9. Two wrong-topic positives and four unsupported questions remain accepted on the new 96. The matched linear arm has 30 correct/5 wrong on that challenge; this better pipeline count does not override the predeclared DEV choice. The policy-only cutoff loses 68 correct on the observed 1,872 while retaining 802 correct/11 wrong. All these controls and loss/error IDs are saved.

Lexical hash interactions and a tiny tanh layer do not establish semantic entailment, factual knowledge, privacy detection or general LLM reasoning. Preserving observed recall does not prove population recall. The fresh pipeline mismatch shows why a named-reference classification score must not be advertised as actual assistant-answer accuracy. Original labels, including previously noted alternate-reference ambiguities, stay unchanged. The default remains the exact frozen v1 model and `production_eligible` remains false.

## Reproduce and verify

```bash
bash scripts/train_contrastive_support.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-contrastive-fixtures
```

CI uses JDK 17 with `--release 8`; the explicit local `--source-target-only` fallback checks targeting without establishing Java 8 API compatibility. All training sources also compile without checkpoint classes through reflective experiment loading.

The training smoke verifies **768** finite-difference coordinates across positive/negative BCE and pairwise objectives for both topologies, every bias role, double/FP32 topology agreement, deterministic snapshots, TRAIN-only pair identity/polarity, group/role rejection and cached-versus-naive calibration. The main module has **125 real JUnit tests**, including cloned weights, topology rejection, strict veto/source identity, concurrency and immutable results.

Committed exports retain the selected model, four matched arms (linear, nonlinear, BCE, ranking) and the policy-only control. Reload checks compare **1,968 selected**, **7,872 matched-arm**, **1,968 policy-only** predictions, **1,968 retained-default** decisions and **236,160 original-reference** checks. Array factories are chunked to avoid large JVM methods.

The packaged host/ART contract checks the selected model **and all four matched arms**, including nonlinear FP32 scores, source/rank preservation and the retained default. Nine corrupt-fixture cases cover flags, missing/duplicate probes, changed references/exemplars, NaN scores and missing/NaN matched-arm probes. API 30 emits a current-run `contrastive-correctness.json`; its exact CI result must be checked before claiming ART passed. Existing v8/v9 provenance and all earlier contracts remain intact.

`host-timings.json` records descriptive interleaved end-to-end host timing with no timing pass threshold. No physical Infinix X689 benchmark, peak-heap result or independent human labels are claimed.
