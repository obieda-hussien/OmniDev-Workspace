# Separate refusal gate experiment

Android parity and measurement harness: [standalone validation](NEURAL_ANDROID_VALIDATION.md).

Starting main: `b07e2e4aef20d3562df759245d27a4596e99cd82`. The previous joint six-class
model improved a narrow English subset but changed the original five-domain classifier,
reducing raw correctness on the old authored regression from 27/30 to 23/30. This new
experiment freezes that original domain head and trains only a binary refusal head.
Both remain outside the APK; production eligibility is always false.

`GatedTextIntentModel` encodes input once, runs the unchanged domain head, preserves its
existing refusals, and can only turn a choice into abstention. It never changes the
domain label or its probability distribution, grants authorization, or executes tools.
Empty lexical input always abstains. Binary output index 1 means refusal. Constructor
checks cover dimensions, encoder version, temperatures and refusal cutoff. The caller
must pair weights with the correct exported metadata; dimension checks cannot prove
semantic weight provenance.

The runner reuses the original/CLINC parsers without changing their behavior. Split and
normalized-text guards precede fitting. The binary head uses the same 260 unique training
rows as the prior experiment, balanced to 320 observations. Only training rows enter
fitting. Architecture choice uses development NLL, temperature uses calibration, and
cutoff uses development rows only: maximize unsupported-topic refusals with zero known
false refusals; on ties choose the highest cutoff from a fixed grid. That budget is a
development constraint, not a guarantee about future requests. Test rows do not select
any settings. Their results are explicitly labelled previously observed regressions.

The returned probability/margin belongs to the frozen domain head, not a combined
calibrated confidence or refusal posterior. Consumers must inspect the final status.

Local result: MLP16 refusal head, 4,146 parameters, temperature 1.5, cutoff 0.6. Domain
and refusal heads together have 8,343 parameters. Results at the unchanged base choice
thresholds (0.60 probability, 0.15 margin):

| Metric | Frozen original | Separate gate | Prior joint model |
| --- | ---: | ---: | ---: |
| Old authored raw correct | 27/30 | 27/30 | 23/30 |
| Old authored accepted correct | 25 | 25 | 23 |
| Old authored choices accepted | 28 | 28 | 30 |
| English known raw correct | 31/40 | 31/40 | 37/40 |
| English required refusals achieved | 18/40 | 34/40 | 37/40 |
| English accepted errors / accepted | 24/54 | 8/38 | 5/42 |
| Old challenge required refusals achieved | 2/20 | 3/20 | 7/20 |

Raw-domain retention is guaranteed by freezing the head, but useful accepted coverage is
not. The counters therefore separately expose known rows accepted and accepted correct
predictions. On this old authored set these also stayed unchanged. The prior joint model
still performs better on several English metrics, and ambiguous/OOD behavior remains
weak. This is a different tradeoff, not a universal improvement or a fresh benchmark.
The CLINC subset is English-only and its rejected topics also occur in training. No new
independent Arabic/mixed test data is introduced. Existing source/license/provenance
notices remain in `neural-core/fixtures/clinc-subset`.

The full-text host benchmark compares three paths using identical frozen domain weights
and sparse-input inference: the original single head, a naive two-encoder gate, and
shared encoding. It includes normalization, encoding, inference and score consumption,
rotates measurement order, and reports median batch means after warmup. Local medians
were about 14–15 microseconds single-head, 29–33 naive dual-head, and 17–18.5 shared gate.
Thus sharing reduces duplicate encoding cost; the gated pipeline is still slower than
the original single head. Timings vary with the shared host/JIT. They are not phone
measurements, single-call percentile latency, app/provider latency or heap measurements.
No speed threshold is asserted in CI.

The existing training script trains, exports, compiles and reloads the refusal checkpoint
with its encoder version, binary label order, temperature and cutoff, checks every corpus
row for checkpoint parity, and verifies frozen domain distributions remain bit-identical.
New JUnit tests cover the refusal-only property, random head combinations, empty input,
unchanged choices and malformed contracts. Existing tests remain intact. Full Android CI
runs asynchronously; a pending check is not reported as passed and merge remains gated.

Before any app integration: independently reviewed bilingual data, a fresh test set kept
out of model decisions, ambiguity/unknown-topic validation, accepted-coverage requirements
and actual API 30 hardware measurements are still required. P0/P2/P5/P6 and tier-specific
authorization remain authoritative. This experiment does not change those paths.
