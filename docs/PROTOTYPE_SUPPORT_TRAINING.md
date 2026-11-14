# Bounded prototype support experiment

This follow-up restores the three earlier-correct cases lost by utility-v2 on the old observed evaluation set. It also gives up some of utility-v2's refusal gains and still loses one earlier-correct mixed-language case on a newly frozen authored challenge. It is **not eligible for production**. No normal APK dependency, permission, provider route, Room schema or P0/P2/P5/P6 completion/recovery contract changes.

## What is fitted

Keep the existing 1,024-dimensional rich-text encoder, original rich/refusal teacher and utility-v2 neural weights fixed. Fit only a bounded support memory from the existing training partition, using supported rows where the teacher's accepted choice equals the label. Normalize each encoded row, sum/normalize within its semantic source group, then average groups equally and normalize again. Produce one FP32 centroid for each domain/language stratum: repository/build/device/knowledge/chat × Arabic/English/mixed.

All 15 strata are required. The fitted bank has 15,360 floats, **60 KiB of raw prototype storage**. Combined teacher/expert weights remain 20,643 parameters; with the prototype bank the raw FP32 arrays total 144,012 bytes, excluding vocabulary, JVM objects and temporary allocations. These are storage counts, not measured peak heap. No utterance text is stored in the runtime bank.

Fit protection floors on previously correct supported **calibration** rows only. Each domain uses its minimum observed prototype similarity minus 1e-4, clamped at zero. Missing calibration support disables adaptation for that domain. This is an empirical protection floor, **not a conformal coverage or correctness guarantee**.

The optional runtime layer may restore the exact original teacher decision when utility-v2 would change or reject it and the teacher-domain prototype score meets that floor. It never upgrades a teacher abstention. It preserves caller confidence/margin thresholds. Construction binds bank, policy and vocabulary fingerprints; arrays, owners, dimensions, finite values, unit norms and budgets are validated and copied. The original utility-v2 constructor/path remains available unchanged.

On development data only, choose guarded adaptation or complete teacher fallback independently per domain. Require strict improvement in language/outcome-balanced utility; ties fall back. Training and development teacher-correct cases must be retained. This selects adaptation for repository/build/chat and fallback for device/knowledge. No test scores select cutoffs, weights or enabled domains.

## Frozen challenge and evaluation limits

`fixtures/prototype-support/protocol.json` and 75 deduplicated challenges were committed locally before the first prototype fit. The generator/provenance manifest preserves hashes and checks overlap against prior corpora and reservations. There are 25 semantic groups with three translations/variants each: 60 supported and 15 refusal cases. These are explicitly **assistant-authored synthetic engineering challenges**, not independently annotated traffic, a public benchmark, or evidence of Egyptian-dialect generalization.

The fitter exports bank/policy sources before reading these challenge rows. Existing scored sets remain observed regressions. After the first evaluation, these authored challenges are also observed and cannot be reused as a new independent test for a future revision. Reports record individual earlier-correct loss identities, including the new failure; no relabelling or threshold repair was performed on that failure.

| Old observed evaluation (1,387 requests) | Teacher | Utility-v2 | Prototype policy |
|---|---:|---:|---:|
| Accepted-correct /339 supported | 192 | 213 | 209 |
| Accepted errors /all requests | 615 | 379 | 482 |
| Unsupported refused /1,048 | 497 | 706 | 610 |
| Earlier-correct supported cases lost | 0 | 3 | **0** |
| Same-domain composition correct /30 | 30 | 28 | **30** |
| Cross-domain composition refused /120 | 44 | 71 | 47 |

The rescued cases are two mixed-language build compositions and one public English joke request. Retention improves, but accepting more unsupported requests is a real tradeoff, not a universal improvement.

| New authored language subset (20 supported +5 refusal each) | Teacher correct /20 | Utility-v2 correct /20 | Prototype correct /20 | Prototype refused /5 | Prototype earlier-correct losses |
|---|---:|---:|---:|---:|---:|
| Arabic | 10 | 10 | 10 | **0** | 0 |
| English | 16 | 16 | 16 | 5 | 0 |
| Mixed | 15 | 14 | 14 | 4 | **1** |

The mixed `authored-encouragement` case is falsely refused. Arabic refusal remains particularly weak. `old_observed_retention_passed:true` does not override `authored_retention_passed:false`, `all_known_retention_passed:false` or `production_eligible:false`.

## Runtime and artifact verification

`DomainPrototypeSupport` is the immutable bounded runtime bank. `UtilityExpertDecisionModel` accepts an optional paired bank/policy. Desktop-only `PrototypeSupportTrainer` and `PrototypeSupportExperiment` fit and evaluate it. Versioned sources/report are under `neural-core/checkpoints/prototype-support-v1`; generated full observations remain CI artifacts.

Local checks performed for this revision:

- 73 JUnit runtime tests, including copying, malformed banks, exact teacher restoration and optional-bank pairing.
- Real-row fitter controls for source-group weighting, partition rejection, missing training strata and disabled routes without calibration support.
- 4,301 observations through freshly exported and committed checkpoints, including metadata/numeric checks, exact dense/sparse and shared/unshared decisions, and preservation of every prior abstention.
- 1,070 new host checks using the **actual packaged Android test contract**, plus the existing 139/699/859/995 suites and 18 deliberately corrupted-fixture rejections.
- Seven new provenance/contamination/report tests; existing data guards and original training/utility export tests remain in place.

The isolated Android test APK includes the bank/policy only in `androidTest`. Its fresh correctness report must contain `prototype_parity_passed` and exactly 1,070 probes. Fixture task inputs include all exporters, contracts and preparation dependencies so incremental builds cannot silently use stale goldens. API 30 ART/Gradle validation is delegated to CI; a successful host contract is not an ART pass. No physical phone validation was performed.

A descriptive host JVM run uses 144 fixed training inputs (24 per expected class including refusal), 512 warm-up calls per mode and eight rotating batches. Median batch means were approximately 39.27 µs teacher, 49.18 µs utility-v2 and 41.47 µs prototype policy, including encoding. Raw batch means are retained in the report. Run order, JIT, contention and the changed fallback policy affect these numbers; they do not establish mobile speed or a statistically reliable speedup.

## Reproduce

With a full JDK 17:

```sh
python3 scripts/test_prototype_data.py
bash scripts/train_prototype_support.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-neural-fixtures
./gradlew :neural-core:test
```

`--source-target-only` is a constrained-host fallback; it does not prove Java 8 API compatibility. CI uses strict `--release 8`. The prototype training job refits the bank, verifies exported and committed policies and uploads reports even when model-quality flags are false. Infrastructure integrity passing must not be confused with model promotion.

The next quality step needs genuinely independent bilingual/mixed-language labels and broader refusal coverage, followed by a preregistered retention/refusal tradeoff. Increasing model size or fitting the newly exposed failure would not provide that evidence. The normal application remains unaffected while that work is evaluated.


## Learned trust follow-up

[EXPERT_TRUST_TRAINING.md](EXPERT_TRUST_TRAINING.md) compares a small learned keep/use gate with this prototype policy. It records the missing mixed-language keep stratum, synthetic training-only paired rehearsal and both failed retention outcomes. No universal improvement or production activation is claimed.
