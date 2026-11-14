# Counterfactual expert trust and paired rehearsal

This experiment learns when to retain the frozen teacher instead of accepting an already eligible utility-v2 proposal. It is small and reproducible, but **neither version passes retention requirements**. V2's development improvement does not establish a better model: it loses more previously correct held-out decisions. Both checkpoints remain experimental; normal APK dependencies, flavors/permissions, providers, Room schema and P0/P2/P5/P6 authority are unchanged.

## Research boundary

The general idea of learning when to defer is discussed in [Mozannar and Sontag, ICML 2020](https://proceedings.mlr.press/v119/mozannar20b.html); temperature calibration is discussed in [Guo et al., ICML 2017](https://proceedings.mlr.press/v70/guo17a.html). This is a custom bounded selector around two frozen local models, not a reproduction of those papers, a human-expert deferral system, a Jev clone or a conformal correctness guarantee. No paper's benchmark or speed result is transferred to Omni.

## Runtime contract

`ExpertTrustDecisionModel` receives an existing `UtilityExpertDecisionModel` and a small two-class trust network. Its only possible outputs are the exact original teacher decision or the exact already eligible utility proposal. It cannot invent a new choice, upgrade a prior abstention, bypass caller confidence/margin, authorize a tool or produce completion evidence.

The optional `review` path shares the original encoder and reuses actual teacher/expert posteriors and domain presence. It does not run in the legacy utility `classify` path. Twenty bounded inputs contain teacher probabilities5, raw expert probabilities6, prior-domain onehot5, domain presence1, raw rejection flag1 and two margins. No private reasoning, arguments, credentials, raw text memory or execution evidence enters the learned selector.

Checkpoint fingerprints bind all frozen neural weights, layer shapes/activations, temperatures, labels, vocabulary, refusal heads and policy thresholds. Sparse execution mode does not change identity. The selector validates its feature schema, base fingerprint, dimensions, parameter budget and copied cutoff array. The fingerprints are computed during optional construction/inspection; ordinary chat and existing classification paths do not perform this hashing.

## V1 training and measured gap

Freeze `fixtures/expert-trust/protocol.json` and the public reservation before fitting. Use only TRAIN rows where an accepted teacher and raw six-class expert disagree. Label `use` only when the expert matches the true label and the teacher does not; label teacher-correct and both-wrong cases `keep`. This includes counterfactual raw expert rejections that the frozen utility policy itself prevented. No model-authored success claim supplies labels.

The actual initial source has592 counterfactuals:553 use,39 keep. Strata are ar:keep20/ar:use281/en:keep19/en:use267/mixed:use5. **Mixed:keep is absent**, and only four examples are raw expert rejections of a correct teacher. Oversampling does not create those missing examples.

Four fits: linear42 parameters or tanh8 with186, seeds42/97. Use the existing seeded SGD trainer, at most200 epochs, development NLL early stopping and available language/target-stratum balancing. V1 balances to1,405 examples. Select temperature on calibration counterfactuals only from .5/.75/1/1.5/2/3/5.

Per teacher domain, select trust cutoffs on development balanced outcome utility from descending1.1/.99/.95/.9/.8/.7/.6/.5/0. Preserve every earlier-correct TRAIN/DEVELOPMENT/CALIBRATION teacher choice; ties retain the stricter cutoff. Include full teacher fallback, prefer smaller networks on equal utility, then NLL and fixed fit order. No test-driven repairs occur after export.

V1 selects tanh8/seed42, best epoch15,40 epochs run, temperature3, cutoffs[.7,.8,.8,1.1,0], development utility.707308. Its extra186 weights use744 bytes of raw FP32 storage; total teacher/expert/trust weights20,829, excluding vocabulary, objects and allocations. It does not need the prototype bank's60 KiB at runtime.

## V2 paired rehearsal

Commit V1 weights, report and failures before separately freezing `rehearsal-protocol.json`. Only original supported TRAIN groups with matching Arabic/English labels may produce `ar / en` and `en / ar` concatenations, at most512 UTF16 characters. Retain the original semantic group/label and training partition; deduplicate normalized text. These are explicitly **synthetic same-intent code-switch rehearsals**, not new independent user utterances.

This adds646 training rows. Counterfactuals increase to768:717 use,51 keep; mixed:keep12/mixed:use169. The six available target strata balance to1,686 examples. Repeat the same four architectures/seeds, stopping rule, calibration grid and development selector. Augmented rows also constrain retention. Do not use development/calibration/test text to generate rehearsal.

V2 selects linear/seed97,42 parameters, best epoch5,30 epochs run, temperature1.5, cutoffs[0,.6,.8,.7,0], development utility.736186. The smaller network uses168 bytes of raw FP32 storage, but loses **four** earlier-correct observed cases instead of V1's two. It is archived as a failed follow-up and does not replace V1 in the standalone Android test fixture.

## Evaluation and limitations

The 4,301 earlier observations are already scored regressions. Their held-out subset now includes the75 previously authored challenges:1,462 rows,399 supported and1,063 requiring refusal. The corpus is a narrow five-domain experimental ontology; requiring refusal does not mean Omni lacks those product capabilities.

| Observed held-out subset | Teacher | Utility-v2 | Prototype-v1 | Trust-v1 | Trust-v2 |
|---|---:|---:|---:|---:|---:|
| Accepted-correct /399 supported | 233 | 253 | 249 | 247 | 251 |
| Accepted errors /1,462 total | 629 | 390 | 495 | 436 | 428 |
| Unsupported refused /1,063 | 505 | 716 | 619 | 678 | 680 |
| Earlier-correct cases lost | 0 | 4 | 1 | **2** | **4** |

V1 retains the two build compositions lost by utility-v2 but still falsely refuses the English joke and mixed-language encouragement. V2 loses all four. Arabic authored supported correctness stays10/20 and refusal0/5. This is insufficient quality; neither optimizer success nor a smaller model overrides the recorded losses.

Before V1 fitting, reserve80 unused official-test observations from the pinned MASSIVE1.1 ar-SA/en-US files:ten parallel groups each for transport tickets, list creation, contact addition and cleaning. Source IDs, normalized text, translations, upstream partition, hashes and CC-BY4 attribution are checked. No positive groups remain unused in the existing supported topics; this new set is **negative-only**, not a fresh overall-accuracy benchmark.

| Initially new negative-only subset | Teacher | Utility-v2 | Prototype-v1 | Trust-v1 | Trust-v2 follow-up |
|---|---:|---:|---:|---:|---:|
| Arabic refused /40 | 23 | 27 | 27 | 27 | 27 |
| English refused /40 | 20 | 29 | 24 | 29 | 28 |

Those80 rows are observed for V2, as explicitly recorded. All future use must also treat them as observed. Reports retain individual losses, `observed_retention_passed:false` and `production_eligible:false`. There is no independent new supported/mixed positive, Egyptian traffic or physical-device validation.

## Verification and integration

Locally performed with available JVM Java8 source/target compilation:

- 80 JUnit tests including exact selection, prior refusal, tied expert, caller bounds, copied policies and foreign/full-base fingerprint rejection.
- Actual training-label, source-group/partition, rehearsal deduplication, same-intent and train/development/calibration retention controls.
- Both exported and committed selectors checked over4,381 observations: exact dense/sparse choices, review-vs-legacy utility parity, feature/score/probability parity and no invented choice.
- All original training/export guards, four utility AdamW refits and prototype refit/parity remain passing.
- Actual packaged Android test contract passes1,150 trust-v1 cases on the host, in addition to existing139/699/859/995/1,070 suites and22 intentionally corrupted-fixture rejections. Fresh ART reports must contain `trust_parity_passed` and count1,150.

The isolated test APK packages V1 only in `androidTest`. V2 remains a desktop training/checkpoint artifact. CI uses strict JDK17 `--release 8`, refits both versions and runs the API30 ART/application suites. A host contract pass is not an ART or physical-phone pass; this revision's CI status is reported separately in its PR.

Descriptive first-run host medians over144 fixed inputs,512 warm-ups/mode and eight rotating batches were25.87µs teacher,24.85µs utility-v2 and26.95µs trust-v1, including encoding. Raw batches are archived. Timings vary substantially on reruns; no phone-speed, peak-heap or statistically reliable speedup claim is made.

Reproduce with a full JDK17:

```sh
python3 scripts/test_trust_data.py
bash scripts/train_expert_trust.sh --initial
bash scripts/train_expert_trust.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-neural-fixtures
./gradlew :neural-core:test
```

`--source-target-only` is a constrained-host fallback; strict API compatibility is a separate CI check. Database schema and normal APK behavior are unchanged. The next quality milestone requires independent supported developer/device/chat examples, carefully reviewed mixed-language keep/use labels and broader Arabic refusals; repeated scoring of these same tests cannot establish that milestone.


## Broader supported supervision follow-up

[CONTRACT_INTENT_TRAINING.md](CONTRACT_INTENT_TRAINING.md) adds source-anchored authored examples while keeping the old representation/optimizer/runtime fixed. It reports protected-policy gains at the same parameter count, and preserves the remaining old/new failures. These authored scenarios do not replace independent traffic or physical-device validation.
