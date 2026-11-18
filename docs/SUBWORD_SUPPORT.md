# Experimental tolerant-token support selector (v7)

V6 refused20 correctly ranked DEV questions;19 failed its literal coverage floor. V7 adds
TRAIN-only tolerant token coverage and a matched augmentation experiment. It improves useful
acceptance on a targeted authored typo/word-form challenge, but increases older regression errors.
The production default remains frozen v1 and `production_eligible` remains false.

## Implementation and bounds

The [Stanford IR text on k-gram indexes](https://nlp.stanford.edu/IR-book/html/htmledition/k-gram-indexes-for-spelling-correction-1.html)
describes finding approximate vocabulary candidates through gram postings. Our implementation uses
padded UTF-16 character3/4-gram sets and Dice overlap, not that text's Jaccard/edit-distance pipeline.
It supplies a coverage hint; it never rewrites the query, FAQ evidence, tool names or arguments.
No embeddings, general language model or statistical error guarantee are introduced.

`TrainTokenCoverage` indexes only the351 original supported TRAIN exemplars. Each unique normalized
word records its exemplar owners, topic/group identities and TRAIN IDF. For a unique query token,
enumerate matching gram postings, keep at most128 vocabulary candidates by shared-gram count,
then choose its maximum Dice match at or above.6 per topic. Exact words win a gram-count tie before
lexical order. Whole excluded TRAIN groups contribute no ownership, including translations. IDF
still comes from the whole TRAIN corpus; this is not fully cross-fitted end-to-end stacking.
IDF-weighted per-topic coverage is bounded to[0,1]. Candidate truncation is deliberate: it is not
equivalent to a complete fuzzy scan above128 candidates. Small-vocabulary dense-oracle tests and
an adversarial exact-match/candidate-cap tie test cover these distinct contracts.

Bounds:512 exemplars,512 UTF-16 query units,64 units per vocabulary word,8,192 words,400,000 gram
postings and64 topics. The actual index has1,103 words,6,379 gram keys and11,529 postings. All
query arrays/maps are local; no query history, service or network request is retained. Bounds
and counts are not whole-model RAM or a physical-phone benchmark.

`SupportSubwordModel` preserves the frozen v5 ranking and original Article references. Its12
features are top base/word/character scores, top/runner soft coverage, top/runner hard coverage,
first-minus-second/third score gaps, signed soft-coverage difference, candidate count and query
length. A bounded binary head estimates first-topic correctness. Model identity binds the exact
base/v5 contract, lexicon groups/text, feature version, head and calibration. Changed exemplar
wording cannot silently reuse a foreign head. Scores never authorize tools or verify task success.

## Frozen experiment and actual training

Protocol, validator and120 new stress queries were published before fitting at
`2de419ffbf3a17f773ef61bb941f419171f2610b`. The40 groups each have English/Arabic/mixed wording:
96 supported questions (29 single-topic and3 compound groups) plus24 near-topic unsupported asks
for undocumented precise information. This is explicitly a targeted, assistant-authored typo/
word-form stress test, not unbiased traffic, independent human labels, unseen documents/topics or
semantic OOD. All29 topics remain searchable. Exact normalized duplicates of the prior1,368
questions and FAQ questions are rejected; semantic independence is not established. These120
scores are now observed and must not be used to retune this experiment.

Compare two matched training arms. The original uses TRAIN411. The augmented arm uses822 views:
each query plus one stable-ID-selected single interior Unicode-codepoint deletion in an alphabetic
span of at least6 codepoints.397 views change;14 repeat the original when no span qualifies.
Every view inherits its source ID, group, language and primary requirements. All translations and
views of that group remain excluded from exemplar/token matching. Neither augmented views nor
DEV/CAL/tests populate the lexicon. These are repeated training views, not411 additional independent
examples. Typo augmentation is mechanically labelled and has no independent semantic review.

Fit16 candidates: in each arm, linear/tanh8 ×L2 0/.01 ×seeds42/97. Reuse the tested v6 binary
AdamW objective, rate.01, batch32, gradient cap5, decay1e-4, at most120 epochs/patience15. Select
actual FP32 snapshots by DEV99 macro-language NLL, then smaller head, original arm and lower seed.
Use the existing disjoint group-hashed CAL48 temperature and CAL51 decision subsets. Search the
same temperature grid and confidence/margin grid, with the coverage threshold applied to the new
soft signal. CAL51 utility is correct minus3wrong with errors no higher than v5; v5 is a fallback
unless utility strictly improves. Export selected and both matched-arm factories before scoring
the new stress test. No fresh score changes the chosen arm.

Selected: original arm, tanh8/seed42/L2 0, epoch33 (48 run), DEV NLL.241679, temperature.75,
confidence threshold.925, soft coverage floor0 and score margin0. The113 FP32 parameters use452
raw weight bytes; this excludes lexicon, v5 indexes, documents, classes and application.

## Results, gains and failures

| Same120 new stress queries | Frozen v5 | Frozen v6 | Selected v7 | Matched augmented arm |
|---|---:|---:|---:|---:|
| First topic correct /96 supported |87|87|87|87|
| All primary requirements within3 /96 |95|95|95|95|
| Accepted correct |24|24|42|48|
| Accepted wrong |0|0|0|0|
| Unsupported refused /24 |24|24|24|24|

This is a targeted acceptance/coverage improvement, not improved ranking. Correct acceptance by
language (32 supported each) changes English10→16, Arabic4→10 and mixed10→16 for the selected
model. Zero observed new-test errors does not establish zero population risk or general accuracy.
The augmented arm has a better stress result, but worse DEV NLL.253522 and was not selected.
Do not switch to it after observing these scores or attribute the selected gain to augmentation.

On the prior1,368 observed regressions, v5 accepts635 correct/4 wrong, v6 accepts652/6, and selected
v7 accepts706/12. V7 loses20 previously accepted-correct v5 cases and introduces10 newly wrong
acceptances while removing two previous errors. Its veto diagnostic accepts615/2. The selected
candidate also loses10 previously correct retained-v1 cases across all1,488 probes. Every ID is
recorded. Old regressions include TRAIN self-matches and DEV/CAL and are not independent accuracy.
These failures prevent production promotion despite the positive stress-set result.

`selector_NLL/Brier/ECE` in each comparison describe the new head under different policies; they
are not probabilities calibrated for old relevance scores or the old v6 head. On the new stress
set they are.381665/.129914/.112586. A warmed host run measured median end-to-end batch means
538,046ns for v5 and606,785.5ns for the selected v7 model (99 warmup queries/arm,8 alternating
batches of32 DEV queries). Raw observations are committed. This is added work, not a speedup
claim. No physical Infinix latency, peak heap, battery or whole-model-memory result is claimed.

## Validation and reproduction

Local Java8 source/target validation includes118 real JUnit tests: new dense/index equivalence,
typo/group exclusion, exact-word cap ties, vocabulary/posting budgets, immutable identity/source
and concurrency cases. Nine reservation guards pass.252 finite-difference coordinates from the
actual augmented-view linear/nonlinear regularized objective pass, with inherited source groups,
deterministic fitting and wrong calibration-role rejection. Repeated complete16-fit runs reproduce
selected weights/policies,1,488 full selected probes and2,976 compact matched-arm predictions.
Compiled and committed selected/original/augmented factories are all exercised. The selected
source/default checks cover14,880 frozen rank hits,29,760 exact original references and1,488
retained-default comparisons.

The optional standalone test APK packages the explicit selected candidate. The same pure Java
host/ART contract checks1,488 predictions, soft scores, original sources and exact defaults.
Seven corrupt fixture cases are rejected, including nonfinite confidence/soft coverage and
foreign exemplar text. API30 must produce current-run `subword-correctness.json` alongside every
older report. Legacy numerical audit scope is pinned to its original source graph so independent
later specialists do not alter that comparison; v7 has its own tests and compiled contracts.

New JDK17 strict Java8, Gradle and API30 results are CI evidence, separate from the local fallback.
No normal APK activation, permissions, Room migration, service, provider or P0/P2/P5/P6 change is
introduced. Corpus text remains reviewed product documentation, not source code/private chats;
source hashes are provenance rather than training input.

```sh
python3 scripts/test_subword_support.py
bash scripts/train_subword_support.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-subword-fixtures
./gradlew :neural-core:test
```

The constrained local compiler accepts `--source-target-only` for training, or as the second
fixture-script argument after its output directory. Future work requires better answerability
labels, independently reviewed data and new reserved evaluation; this targeted gain does not
resolve all unsupported-request or retention failures.
