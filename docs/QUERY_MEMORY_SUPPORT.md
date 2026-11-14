# Experimental support query memory (v5)

V4's larger dataset did not improve its fixed lexical head. DEV/CAL errors often use wording
missing from the short FAQ. V5 adds a bounded **TRAIN-query retrieval index**, while returning
only original reviewed documentation. It is optional experimental retrieval for potential
larger-model context, not a conversational model, title generator, action authorizer or completion
verifier. P0/P2/P5/P6, ordinary chat, edition limits and the normal APK remain unchanged.

## Research motivation and actual implementation

[Document Expansion by Query Prediction](https://arxiv.org/abs/1904.08375) explores representing
documents with questions they can answer. [Doc2Query--](https://arxiv.org/abs/2301.03266) studies
harmful generated queries and their filtering. They motivate checking vocabulary mismatch and
query quality. We do **not** implement their seq2seq models, generate query expansions, concatenate
questions into evidence, or claim their benchmark results.

Our index uses the existing90 supported original TRAIN questions plus261 supported v4 TRAIN
questions: **351 explicitly authored exemplars**, not private user conversations. Each stores its
stable ID, complete scenario group, topic and bounded text. TF-IDF cosine views use word unigrams/
bigrams and character3/4 grams. A topic gets the maximum exemplar similarity in each view. The
third signal is its frozen v4 document-field score. This is lexical similarity, not semantic
embeddings or a general reasoning capability.

`SupportQueryMemory` validates documents against the base corpus, binds every exemplar's text,
group and topic through a versioned SHA256 fingerprint, rejects duplicate identities/conflicting
groups/unknown topics, and returns immutable original Article references. Training text is a
ranking hint, never a quoted fact or runtime execution evidence. Metadata/status/edition labels
remain attached to their original references and cannot grant permissions.

The index allows at most512 exemplars,512 UTF-16 units/query/example,128 corpus documents and100,000
terms/view. The actual deployment fixture has351 exemplars and58 FAQ documents/29 topics. Indexes
are immutable and queries are local; no persistent service, network call or query-history cache.
These bounds are not a phone heap/latency benchmark. Index text and sparse maps cost much more
than the few scalar fusion coefficients.

## Training without direct exemplar self-matching

Training remains411 original/v4 TRAIN rows. The memory comes from supported TRAIN rows only;
DEV, CAL, old tests and the new challenge never populate it. When computing a TRAIN query's
features, exclude its **whole scenario group including all translations** from exemplar matching.
IDF statistics remain fitted on the entire TRAIN exemplar corpus; exclusion concerns matching,
not rebuilding IDF for each fold. The frozen base head was already fitted on TRAIN. This is not
fully cross-fitted end-to-end stacking or independent-topic evaluation.

Four projected pairwise-logistic fits use three convex weights, rates .05/.15 and seeds42/97,
batches16, at most8 hardest negatives/query, gradient cap5, L2.01 toward uniform, max80epochs and
patience10. Word-only, character-only, frozen-base-score and uniform controls also compete. V4
DEV99 selects top1/MRR/fewer fitted parameters with per-language floors against frozen v4; CAL99
selects score/margin/query-coverage thresholds using correct minus3wrong. The selection snapshot
and factory are written before fresh scoring. All1,101 older queries are observed regression only.

**The uniform control wins.** Its three weights are1/3, so the new fusion head has zero fitted
coefficients; four actual learned fits were evaluated and lost. The exported Dense stores three
fixed coefficients plus a fixed zero bias (16 raw FP32 bytes). Its frozen base additionally stores
nine FP32 values (36 bytes). Those52 scalar bytes exclude351 exemplar texts, vocabulary, sparse
vectors, classes and the rest of the application. Do not call this a sixteen-byte whole model.
Selected acceptance: minimum0, score margin.05, coverage.5. Scores are relevance, not probabilities.
Coverage uses the maximum query-token overlap with a topic's TRAIN exemplars or original field.
The base-only control is a ranking ablation; it still uses this shared coverage calculation.

## Reserved authored evaluation

138 new authored queries:105 supported (87 single,18 compound) and33 unsupported, grouped into
46 scenarios with English/Arabic/mixed translations. All29 FAQ topics remain searchable and were
also seen during training. Exact normalized overlaps with old1,101 queries/canonical questions
are rejected, but semantic independence is not established. Assistant-authored labels have no
independent human review; this is not real traffic, unseen-document, semantic-OOD or held-out-topic
performance. The fixture/protocol was committed locally before fitting and has now been observed.
A subsequent fix aligned the trainer's accumulation with the runtime's double-then-float contract
before reviewing fresh metrics; repeated reproduction verifies the corrected path.

| Fresh authored metric | Lexical | Retained v1 guard | Frozen v4 | Query memory |
|---|---:|---:|---:|---:|
| First topic correct /105 |77|76|78|91|
| MRR |.808605|.799871|.820233|.914218|
| All primary requirements within3 /105 |86|85|90|97|
| nDCG@3 |.796316|.787983|.819361|.910376|
| Accepted correct |36|10|26|26|
| Accepted incorrect |2|0|2|0|
| Unsupported refused /33 |33|33|33|33|

| Language (35 supported each) | Lexical top1 | Frozen v4 | Query memory |
|---|---:|---:|---:|
| English |27|27|31|
| Arabic |19|23|28|
| Mixed |31|28|32|

Top1 means one primary topic, not every requirement. All-primary@3 requires each primary reference
among the first3; selective correctness concerns only the first accepted topic, not completion of
the user's task. Correct acceptance remains26/105: low coverage despite the ranking gain. English
correct acceptance drops7 to6 versus v4. Zero observed fresh false acceptances is not a population
guarantee or independent clinical/security assurance.

The old1,101-probe regression includes TRAIN self-matches at inference and DEV/CAL, so its numbers
are not held-out accuracy: candidate791/891 supported top1 versus v4's688;577 correct acceptances
but4 incorrect, versus retained guard193 correct and1 incorrect. The candidate therefore still
fails an observed safety gate. Every lost previously accepted-correct query is retained in
parity-retention.json. Default behavior stays frozen v1; no normal APK activation.

## Reproduction and Android contract

```sh
python3 scripts/test_query_memory.py
bash scripts/train_query_memory.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-memory-fixtures
./gradlew :neural-core:test
```

JDK17 is used in CI for strict Java8 APIs. `--source-target-only` is a constrained local fallback,
not strict API proof. New runtime JUnit tests cover whole-group/translation exclusion, original
immutable evidence, corpus/exemplar/version binding, convex weights, bounds and concurrent
inference. Numerical tests check12 finite-difference coordinates on actual group-excluded TRAIN
rows, attribution and calibration barriers. All1,239 old/new queries verify compiled predictions,
independent retained-default behavior and exact original references.

The standalone test APK packages the explicit candidate, original documents and pinned351-exemplar
asset. `QueryMemoryProbeContract` verifies1,239 predictions/defaults and source identity. The host
smoke rejects corrupt fixtures; API30 instrumentation writes memory-correctness.json and the CI
harness requires its current run ID. Host execution is not ART or physical-phone evidence. Older
specialist and support contracts remain enabled.

No Room migration, root dependency, privileged capability expansion or provider change is added.
No physical Infinix latency, peak heap or battery measurements are claimed. Independent review of
labels, genuinely new scenario evaluation and acceptable selective coverage remain necessary.

The runtime uses an inverted term/posting index instead of scanning every stored sparse vector.
Query terms keep their sorted dot-product accumulation order; all1,239 prediction rows, decisions
and score/coverage decimals remain byte-identical to the pre-optimization export. In two separate
host runs, median means over5 batches of32 rotating DEV queries were8.160ms before and1.551ms after;
the corresponding frozen-v4 timings were1.262ms and1.042ms. Warmup20 queries; memory factory/exemplar
initialization142ms in the latter run. These are descriptive end-to-end host observations, not
individual-request p50s, controlled hardware speedup guarantees or physical-phone benchmarks.
Raw observations are in host-timings.json; future CI timings are artifacts, not equality gates.

Local verification on2026-11-14:102 actual JUnit tests,8 new data guards,12 actual gradient
coordinates, repeated four-fit/selection reproduction,1,239 compiled predictions/default checks
and24,780 original-reference checks passed. The candidate loses24 previously accepted-correct
results across combined old/new splits; every ID remains recorded. Packaged host contracts pass;
new ART/Gradle/strict Java8 API checks remain CI evidence, not local device results.
