# Experimental support correctness selector (v6)

V5 retrieves useful documentation but accepts only a fraction of correct first-topic matches.
V6 trains a small model to estimate whether that first topic is appropriate. It preserves the
ranking and exact original FAQ references. This is an offline correctness-selection experiment,
not a conversational language model, trained title generator, tool authorizer or completion proof.
The normal APK continues to use its existing behavior; the exported production default remains
the frozen v1 support guard. All production-eligibility flags remain false.

## Research and implemented scope

[Selective Classification for Deep Neural Networks](https://proceedings.neurips.cc/paper/2017/hash/4a8423d5e91fda00bb7e46540e2b0cf1-Abstract.html)
studies rejection as a way to trade coverage for prediction risk.
[On Calibration of Modern Neural Networks](https://proceedings.mlr.press/v70/guo17a.html)
studies confidence calibration, including temperature scaling. They motivate measuring correctness
and coverage separately and checking a held-out calibration partition. We implement a small binary
selector and a finite temperature grid. We do not implement SGR, its statistical risk bounds,
SelectiveNet, semantic embeddings or any benchmark results from those papers. No population error
guarantee follows from a zero-error authored test.

## Frozen protocol and data

The protocol, validator and129 new questions were published at commit
`9b6afa6ad84a352715b1186ec4805ffb2e50080a` before fitting this head. There are43 scenario groups,
each translated into English/Arabic/mixed:99 supported questions (29 single-topic groups and4
compound groups) plus30 unsupported questions. Unsupported questions request precise pricing,
architecture, corpus, region or performance information absent from the58-document FAQ. They
are authored near-topic challenges, not independent human annotations, real traffic, held-out
topics or semantic OOD. All29 topics remain searchable. Exact normalized overlaps with the old
1,239 questions and canonical FAQ questions are rejected; semantic independence is not established.
These questions have now been observed and cannot be reused as a fresh tuning target.

Training uses only the original/v4 TRAIN411. The v5 base, weights and351 supported TRAIN exemplars
remain frozen. A training query excludes its entire scenario group, including every translation,
from matching. IDF and the frozen base still originate from the full TRAIN data: this is not fully
cross-fitted end-to-end stacking. The target is whether the frozen first topic belongs to the
question's primary requirements.268 TRAIN rows have a correct first topic; wrong-topic and
unsupported rows are negatives. Empty candidate lists always abstain at runtime.

The selector receives12 bounded features: top/runner-up base, word and character scores (6),
their two coverage scores, first-minus-second/third score margins, bounded candidate count and
normalized token count. It receives no topic identity, text encoding, private conversations,
source code, credentials, logs or runtime execution evidence. Hashing source files for provenance
does not ingest them as training text.

Compare8 actual fits: linear/tanh8 ×L2 0/.01 ×seeds42/97. Reuse the existing12-input binary
network and its analytical gradient; use mini-batch32 AdamW, rate.01, displacement decay1e-4,
gradient cap5, at most120 epochs and15-epoch patience. DEV99 macro-language NLL selects actual
FP32 runtime snapshots; smaller parameter count breaks equal-loss ties. No test score chooses
weights, epochs, temperature or policy.

Group-hashed v4 CAL99 is divided into disjoint CAL48 temperature and CAL51 decision subsets.
The finite temperature grid is.5/.75/1/1.5/2/3. The decision grid searches threshold0..1 by.025,
coverage0/.25/.5/.75 and margin0/.025/.05/.1, maximizing correct minus3wrong with errors no higher
than frozen v5 on that subset. Frozen v5 is retained as an experimental-policy fallback unless
utility strictly improves. Source IDs and typed calibration roles prevent fitting/calibration
mix-ups; translations never span these two subsets. The subsets are small and do not establish
universal calibration.

Selected: tanh8, seed42, L2 0, epoch68 (83 run), DEV NLL.262031; temperature1, probability
threshold.625, coverage.5, score margin0. The113 FP32 parameters occupy452 raw weight bytes;
this excludes the complete v5 indexes, documents, objects and application. Temperature1 means
no temperature rescaling gain was selected.

## Results and failed promotion requirements

| Same129 newly authored questions | Frozen v5 | Learned selector | Learned veto of v5 |
|---|---:|---:|---:|
| First topic correct /99 supported |90|90|90|
| All primary topics within3 /99 |94|94|94|
| Accepted correct |32|32|32|
| Accepted wrong |0|0|0|
| Unsupported refused /30 |30|30|30|

There is **no demonstrated new-test acceptance improvement**. Correct acceptance by language
remains English13, Arabic5 and mixed14 out of33 supported each. The head cannot repair missing
or misranked references because it never changes ranking. Low useful coverage, especially Arabic,
remains unresolved. First-topic acceptance is not verification of every compound requirement.

On DEV99, accepted-correct rises45→52 with zero errors. On the CAL51 decision subset,21 correct/
1 wrong becomes22/0. These are selection/calibration results, not held-out improvements.

On the old1,239 observed regressions, frozen v5 accepts603 correct/4 wrong; the learned selector
accepts620/6. It loses two prior correct acceptances and introduces four newly wrong acceptances
while removing two earlier wrong ones. The veto accepts601/2, trading away two correct cases.
These old results include TRAIN self-matches and DEV/CAL: they are regression evidence, not
independent accuracy. Every loss and newly wrong ID is recorded in `parity-retention.json`.
The selected experimental policy also loses26 previously correct retained-v1 acceptances across
all1,368 questions. These failures prevent promotion; the production default is preserved exactly.

Fresh selector NLL.430128, Brier.137459 and ten-bin ECE.085350 are descriptive finite-sample
measurements. `selector_*` metrics in every policy control refer to this same head's predicted
correctness, not to calibrated probabilities for v5's relevance score. `risk-coverage.tsv`
contains the score-sorted fresh predictions for independent analysis, never policy tuning.

One warmed host run with99 warmup queries/arm and8 alternating batches of32 DEV queries measured
median batch means687,536.5ns for v5 and704,857ns for the selector. Raw batch observations are
saved. This is additional inference work, not a speedup claim or a phone benchmark. No physical
Infinix latency, peak heap, battery or whole-model-memory measurement is claimed.

## Contracts and reproduction

`SupportReliabilityModel` is bounded, immutable and query-local. Checkpoint identity binds the
feature version, corpus/exemplars, actual v5 and base ranking weights/mode, frozen acceptance
policy, selector weights, temperature and thresholds. Changing a base mode, weights or policy
cannot silently reuse a foreign selector checkpoint even when its document fingerprint matches.
The result exposes the candidate, learned and frozen acceptance flags separately. Returned
references and rank scores remain original; no model score grants capabilities or proves an action.

Local verification includes110 real JUnit tests (6 new),9 reservation/data guards,252 finite-
difference coordinates from the actual linear/nonlinear regularized objective, repeatable learned
snapshots and wrong-role/foreign-group rejection. Repeated8-fit runs reproduce selected source,
policy and1,368 compiled predictions. The compiled and committed checkpoint tests compare1,368
exact retained defaults,13,680 unchanged rank hits and27,360 original references. All older
packaged host contracts and the new1,368-query contract pass;6 corrupt new fixtures are rejected.
Source-only fingerprint API additions are followed by unchanged v3/v4/v5 reproduction checks.

The optional standalone test APK packages the explicit selector. API30 instrumentation checks
the same numeric/source/default contract and writes `reliability-correctness.json`; the harness
requires its current run ID and counts alongside every previous report. New JDK17 strict Java8,
Gradle and ART CI results are separate from local Java8 source/target fallback results. No new
permissions, Room migration, service, provider, normal-APK activation or P0/P2/P5/P6 change is added.

```sh
python3 scripts/test_support_reliability.py
bash scripts/train_support_reliability.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-reliability-fixtures
./gradlew :neural-core:test
```

For the constrained local JDK, pass `--source-target-only` to training, or as the second argument
after the fixture-output path. Committed reports contain actual failed gates. Next work needs
independently reviewed answerability labels, better multilingual candidate coverage and separately
reserved evaluations; increasing head complexity alone has not solved the coverage problem.
