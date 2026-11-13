# Independent domain-presence follow-up

The [binary-refusal experiment](BILINGUAL_REFUSAL_TRAINING.md) exposed weak rejection
of cross-domain requests. This follow-up trains explicit independent domain-presence
labels rather than collapsing every multi-domain request into one heterogeneous reject
class. It uses the same already-observed corpus; **all evaluation results are regressions,
not independent confirmation**. The [fixed protocol](../neural-core/fixtures/bilingual-refusal/multi-intent-protocol.json)
and [actual exported weights/report](../neural-core/checkpoints/multi-intent-v1/) are retained.

Each of five domains has a binary linear absent/present head. Known rows activate one
domain; composed cross-domain rows activate both verified parent domains; unsupported
and vague rows activate none. Parents, scenario groups, source partitions and normalized
duplicate guards remain unchanged. No old challenge enters fitting.

For each head, two seeds use the existing early-stopped trainer, balanced by target and
language. Development macro NLL chooses the seed/best epoch. Calibration macro NLL
chooses its temperature. A shared threshold and either multi-domain-only refusal or
unique-matching-domain refusal are selected **only on development**, under the same 5%
per-language extra-known-refusal budget. Ties prefer fewer known losses then the first
configuration. No numerical quality target is asserted on regression/test observations.

The five selected linear heads have 2,570 FP32 parameters total. Their selected epochs
are 20/5/5/10/5 in repository/build/device/knowledge/chat order, all seed 97; temperatures
are 1/0.5/0.75/2/0.5. Development chose threshold 0.75 and multi-domain-only refusal.
The complete original+prior-gate+presence system has 10,913 parameters. Head count is
fixed at five and additional parameters are capped at 10,000 in the adapter.

`MultiIntentRefusalModel` encodes once and reuses `GatedTextIntentModel`'s internal
feature path. It first respects every prior refusal, then may refuse a choice if
multiple domains are active. The optional stricter policy also refuses zero active
domains or a unique domain that disagrees with the prior proposal. Neither policy may
invent/rewrite a label, change the original probability distribution, authorize tools,
or upgrade a refusal. Returned probabilities belong to the original classifier; they
are not calibrated joint confidence for the presence detector. Arrays/heads are copied
or immutable, and malformed dimensions, budgets, temperatures and thresholds are rejected.

## Observed tradeoff: still not production eligible

| Observed group | Previous refusal gate | Presence adapter |
| --- | ---: | ---: |
| Cross-domain composed refusals | 28/120 | 58/120 |
| All regression required refusals | 62/160 | 92/160 |
| Regression accepted correct / known accepted | 85/90 | 80/85 |
| Regression accepted errors / accepted | 103/188 | 73/153 |
| Original authored accepted correct / accepted | 25/28 | 24/27 |
| Same-domain composed accepted correct | 30/30 | 26/30 |
| New-test required refusals | 72/92 | 72/92 |
| Held-out English topic refusals | 25/40 | 25/40 |
| Old challenge refusals | 3/20 | 4/20 |

Explicit domain presence rejects more mixed requests, but also rejects five additional
correct known regressions: one original and four same-domain compositions. Development
coverage constraints do not prevent that distribution shift. Unknown-topic rejection
does not improve. This candidate **must not be described as an unconditional accuracy
upgrade or silently enabled**. It remains outside the app dependency. Its trained
presence checkpoints are not included in the Android fixtures and this adapter's
behavior has not been exercised on ART; no ART/phone or latency claim is made for it.
The earlier binary cascade's separate ART checks do not establish this new adapter's
Android result. No P0/P2/P5/P6, tier permission, provider, Room or chat path changes.

## Reproduce

```bash
bash scripts/train_multi_intent.sh
# Recompile and check an existing local artifact directory without fitting the ten candidates again:
bash scripts/train_multi_intent.sh --verify-artifacts
```

The script exports five independent checkpoint classes and a versioned-order manifest,
compiles them, and checks their fitted typed decisions on all 1,551 rows. It also checks
metadata, exact dense/sparse parity and preservation of original labels, scores and
refusals. The same observations are checked again against the committed checkpoints.
JSON reports identify corpus/protocol/checkpoint hashes. The verifier rejects stale
metadata or changed source hashes. Unit tests cover multiple/zero/foreign domain votes,
prior refusal preservation, caller-array mutation and malformed/budgeted contracts.

Local source/target-8 fitting and compiled checks were run. Strict `--release 8`, JUnit
and application regressions for the revised PR remain CI work until observed passing.
This is an independent binary-head baseline, not ProtoNN, SetFit, SNGP, fastText, Jev
or a trained semantic language encoder. The [research review](NEURAL_ALGORITHM_RESEARCH.md)
records those separate candidates and their limits. The next experiment needs improved
representations and fresh reviewed bilingual data rather than tuning these now-observed
regression thresholds to make a better-looking report.
