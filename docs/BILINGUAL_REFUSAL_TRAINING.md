# Bilingual hard-negative refusal training

Starting main: `3394cc832e359343109a736de9e218ca23032926` (PR #252).
This is a real offline training/evaluation experiment, not a production promotion,
Jev/RLCD implementation or completion of P9. The original domain classifier is frozen.
Production eligibility is false throughout. No app dependency, Room schema, provider,
tool authorization, P0/P2/P5/P6 completion or flavor-permission behavior changes.

## Data and fitting

[`bilingual-refusal`](../neural-core/fixtures/bilingual-refusal/NOTICE.md) records
the public source revision/license, selection rules, authored inputs and synthetic
composition recipe. There are 1,551 normalized-unique observations: 796 fitting,
182 development, 181 calibration, 132 new test and 260 previously observed or
synthetically composed regressions. Fitting repeats the six outcome/language
strata to 1,752 observations. More balanced observations are not more unique data.

Additional English CLINC examples preserve upstream partition IDs. Four test-only
intent categories never enter the selected fitting/selection intents. Arabic/mixed
negative examples are authored synthetic fixtures, not representative collected
traffic or human-reviewed annotations. Same-domain composed positives counter the
shortcut of rejecting every conjunction; cross-domain compositions require refusal
under this single-label contract. Parents retain their original scenario split.

Dataset and protocol were written before fitting. Full-200-epoch development runs
showed overfitting, e.g. seed-42 MLP16 macro NLL 2.783934 versus linear 0.743503.
Based on those development observations, before inspecting test outcomes, early
checkpoint selection was added: evaluate development macro-stratum NLL every five
epochs, retain a copied immutable best model, stop after 25 epochs without improvement,
and always evaluate the final maximum epoch. The legacy `fit` path retains its full
epoch behavior and update order. It shares the implementation instead of duplicating
the optimizer. Validation dimensions, bounds and fitting-group disjointness are checked.

The fixed search has six candidates: widths 0/16/32, seeds 42/97, at most 200 epochs.
Development macro NLL chooses architecture/checkpoint; calibration macro NLL selects
temperature. Development alone chooses a cutoff from the fixed grid, maximizing
refusal recall under a per-language 5% budget on additional refusals of known base
choices. This is a development constraint, not a future false-refusal guarantee.

Best development scores were 0.538330 (linear, seed 42), 0.540730 (linear, seed 97),
0.778097/0.709297 (MLP16), 0.763990/0.718673 (MLP32). All best snapshots were at epoch
5 and stopped at epoch 30. The selected head has 514 FP32 parameters, temperature
1 and cutoff 0.9; with the original domain head, 4,711 parameters. Smaller won this
development comparison. That is not a general claim that linear models are superior.

## Results and failed improvement

The versioned [checkpoint and complete report](../neural-core/checkpoints/bilingual-refusal-v1/)
are retained for reproduction. The broader-data head performed **worse** than the
previous English refusal head on the new test. It must not replace it on quality claims.

| Evaluation | Previous gate | New head alone | Refusal cascade |
| --- | ---: | ---: | ---: |
| New-test required refusals achieved | 72/92 | 59/92 | 72/92 |
| New-test accepted errors / accepted | 26/54 | 39/67 | 26/54 |
| New-test accepted correct / known accepted | 28/34 | 28/34 | 28/34 |
| Test-only English topic refusals | 25/40 | 22/40 | 25/40 |
| Composed cross-domain regression refusals | 28/120 | 32/120 | 32/120 |
| Old challenge refusals | 3/20 | 3/20 | 4/20 |
| Original authored raw correct | 27/30 | 27/30 | 27/30 |
| Original authored accepted correct / accepted | 25/28 | 25/28 | 25/28 |
| Same-domain composed accepted correct | 30/30 | 30/30 | 30/30 |

The cascade was introduced after individual-head test results were observed, so its
evaluation is explicitly labelled a **follow-up on now-observed regressions**. It is
not a new independent test. No parameters or cutoffs were refit for the cascade.
It uses both locked heads, encodes once, and any head may refuse; neither may invent
a label, change original scores or upgrade an existing refusal. At most two immutable
heads are accepted, and the caller's array is copied. Total parameters are 8,857,
versus the previous original+gate 8,343. There is additional work when the second gate
is reached; no new cascade latency or phone-speed improvement is claimed.

The cascade passed the development coverage budget. Observed known accepted/correct
counts were unchanged in these reported groups, but future false refusals can increase.
The composed/old-challenge improvement is small and unknown-topic rejection remains
poor. New Arabic and mixed test subsets have only four negative rows each and **no
positive rows**; they cannot support a balanced Arabic accuracy or false-refusal claim.
Shared composition templates and previously observed cases limit all these results.

## Reproduction and verification

```bash
python3 scripts/prepare_bilingual_refusal.py --verify
python3 scripts/test_bilingual_refusal.py
bash scripts/train_bilingual_refusal.sh
```

The script compiles actual runtime/training classes, verifies nine corrupted corpus
variants, tests retained early-stop snapshots against an equivalent fixed-epoch fit,
fits all six candidates, exports/compiles the selected model, refits deterministically,
checks metadata and exact loaded logits, and compares reports. A separate comparison
checks the committed checkpoint against the new fit on all 1,551 observations (logit
tolerance `1e-6`, identical refusal decisions) to allow small cross-JDK math differences.
Recorded source hash also identifies the exact committed checkpoint. No timing/accuracy
improvement assertion is used to turn a poor result into green CI.

The original training suite is rerun to catch legacy-path regressions. The existing
standalone Android harness now includes the committed gate in its **test APK only**
and adds host/cascade golden decisions over the same 139 public regression inputs.
It checks unchanged domain scores, preservation of earlier refusals and exact
dense/sparse parity. The separate API 30 job must actually pass before claiming ART
verification. The four-mode timing benchmark still measures the original single gate,
not the new cascade. No physical-device test or new memory benchmark was performed.

Local source/target-8 training, deterministic refit and host checks were executed.
That fallback alone is not strict Java-8 API compatibility or Android compilation.
CI performs full `--release 8`, JUnit, retraining and ART validation; pending checks
must be reported as pending, and merge remains gated on checks/reviews.

## Next comparison

The [primary-source research review](NEURAL_ALGORITHM_RESEARCH.md) distinguishes
fastText, ProtoNN/Bonsai, SetFit, SNGP, energy scores, conformal methods and newer
multi-cluster/selective routing work. None is implemented or benchmarked here.
The evidence points toward testing a better sentence representation and a bounded
distance/prototype baseline with an independent bilingual evaluation, rather than
assuming more epochs or hidden units will improve quality. This is an engineering
inference, not proof that a particular next algorithm will win.
