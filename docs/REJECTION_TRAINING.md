# Explicit rejection training and optional sparse inference

Starting main: `71d1c6b01c3cc4f490bd4789be6adda5cd790417`. The earlier model had no learned
rejection class. This experiment adds `__reject__`, 360 attributed English utterances
from a pinned CLINC150 revision, and class-balanced training. See the subset's NOTICE,
LICENSE and provenance manifest. Its remapped eight-intent subset is not a CLINC150
benchmark score. Domain mappings are ours; the text and upstream annotations are external.

Training uses the original 60 authored examples plus 200 external train examples, balanced
by deterministic within-class repetition to 600 observations. Development and calibration
each use 15 authored and 40 upstream validation examples. All scenario IDs stay within
one split; duplicate normalized text is rejected. No test row or old challenge is used
for fitting, architecture choice, temperature or thresholds. Linear/MLP16 choice uses
development NLL; temperature uses calibration. Probability/margin thresholds remain
0.60/0.15. Configuration is frozen now that test results have been observed.

`RejectingTextIntentModel` converts a winning rejection label into ABSTAIN, preserving
the score distribution for auditing. `Decision.asAbstention()` can only refuse; it cannot
upgrade an abstention. Neither method invokes tools, grants permissions or verifies tasks.

Initial local result (MLP16, 4,214 parameters, temperature 0.50):

| Evaluation | Original model | Rejection-trained model |
| --- | ---: | ---: |
| External English known-label raw correct | 31/40 | 37/40 |
| External required refusals achieved | 18/40 | 37/40 |
| External accepted errors / accepted | 24/54 | 5/42 |
| External coverage | 54/80 | 42/80 |
| Old authored known-label raw correct | 27/30 | 23/30 |
| Old challenge required refusals achieved | 2/20 | 7/20 |

The external improvement comes with lower coverage and a regression on the old authored
test. It is not a general bilingual improvement or production acceptance. Old authored
holdout/challenges are known regressions, not fresh evidence. `production_eligible` stays
false. No APK dependency, active model, runtime authorization or P0/P2/P5/P6 path changes.

`TinyNeuralRuntime.withSparseInput()` is opt-in and shares immutable weights. It skips
zero-valued inputs only in the first layer when at least half the inputs are zero and
the width is at least 64. Other layers and dense inputs retain the original path.
Nonzero terms keep their original accumulation order; zero sums replay the dense loop
to preserve signed zero. Default inference remains dense. Tests cover raw float-bit
parity, activations, density fallback, multiple layers and invalid inputs; the experiment
also checks dense/sparse parity on every corpus row and compiled checkpoint parity.

The runner warms and interleaves both paths, then reports the median of 12 batches of
1,000 inferences using pre-encoded original training inputs. Local kernel batch medians
were approximately 4.4–4.5 microseconds dense versus 2.2 microseconds sparse. These are
shared-host observations, not phone results, single-call percentile latency, end-to-end
text timing, a fixed speedup guarantee, or heap measurements. Timing is not a CI assertion.

Run the existing `scripts/test_neural_training.sh` check. The new checkpoint is exported
temporarily with encoder version, label order and temperature, compiled and reloaded.
Future deployment requires fresh independent bilingual evaluation, regression repair,
OOD/ambiguity coverage and actual API 30 hardware latency/memory measurements. Do not
tune against the already-observed tests and report them as unseen generalization.

The [separate refusal gate](SEPARATE_REJECTION_GATE.md) explores freezing the original
domain head to avoid the joint model's domain-score regression. It retains explicit
accepted-coverage counters and includes whole-text timing, not just kernel timing.
