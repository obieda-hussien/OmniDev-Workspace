# Documentation support retrieval experiment

## Result and current boundary

Four real bounded AdamW fits trained a tiny neural **document reranker**, not a generative language model. The selected 12-input/tanh8/one-output network has **113 learned FP32 parameters (452 raw weight bytes)**. The corpus, index, cached lexical features, JVM objects and allocations cost additional memory. This is not a 452-byte total assistant.

The intended role is to retrieve original documented Omni facts for a larger model to explain. It cannot independently diagnose a live device, invent a setting, authorize a tool, generate a chat title or prove task completion. The normal APK does not enable this experiment; the history title/category path remains its separate local baseline. Earlier intent routing checkpoints and their failed retention gates are unchanged.

The raw learned reranker improved Recall@3 slightly but regressed first-result accuracy and unsupported-question rejection. It **must not replace the lexical baseline**. An additional baseline-veto wrapper reduced accepted errors on the already observed probes at the cost of coverage. That wrapper was introduced after the first evaluation; its result is explicitly a post-hoc safety diagnostic, not fresh held-out accuracy or proof of production quality. Production eligibility remains false.

## Reviewed documentation and frozen questions

Training reads only the reviewed FAQ export: 58 English/Arabic documents across 29 product topics. Source code, user transcripts, credentials, database rows and logs are not training text. Source-file hashes in the provenance manifest pin the implementation; hashing code is not using it as training examples.

The authored protocol and 234 query rows were committed before fitting. Entire FAQ topics, including both paraphrases in each of English/Arabic/mixed language, share one partition. Fifteen topics train, four develop, four calibrate and six test. Twenty unsupported question families are split 8/4/4/4 with their translations together. Counts:

| Partition | Supported questions | Unsupported questions | Total |
|---|---:|---:|---:|
| Train | 90 | 24 | 114 |
| Development | 24 | 12 | 36 |
| Calibration | 24 | 12 | 36 |
| Initial test | 36 | 12 | 48 |

All 58 FAQ documents remain searchable in every partition, as in a fixed-corpus retrieval task. Held out query topics do **not** mean unseen documents. Questions and relevance/unsupported annotations were deliberately authored during this engineering work, without independent human review. Exact unknown values, absent logs, private information and undocumented features are outside this narrow support corpus. Some limitation questions can still have useful contextual references; independent answerability review is required. The initial test is now observed and must not be presented as unseen in future iterations.

The validator pins the FAQ export and query hashes, enforces group/split isolation, rejects normalized duplicate questions and invalid labels, and requires supported/unsupported examples in all three languages per partition. It generates document tables from the allowlisted FAQ export only.

## Runtime and training architecture

ProductSupportModel reuses OmniRamLite candidate retrieval and TinyNeuralRuntime inference. It can score only the existing top 20 quote hits and collapses duplicate topic results. Twelve generic lexical pair features describe query/quote/question overlap, IDF, character trigrams, language mix, service-token overlap, query length and lexical score. No expected topic, query ID, split identity or permission enters inference features. This is lexical feature learning, not a pretrained semantic encoder.

Four mini-batch AdamW fits compare linear/tanh8 with seeds 42/97. The positive class receives bounded weighting for class imbalance. Learning rate .01, batch 64, gradient norm cap 5, weight decay .0001, maximum 100 epochs and patience 15. Development MRR selects checkpoints with NLL tie-breaking. Temperatures are not fitted. Calibration alone selects minimum-score and margin policies using accepted-correct minus three times accepted-wrong. Both network and policy sources were exported before initial test scoring.

| Fit | Selected epoch / epochs run | Development MRR | Development NLL |
|---|---:|---:|---:|
| Linear seed42 | 2 /17 | .705026 | .457506 |
| Linear seed97 | 2 /17 | .695536 | .465606 |
| Tanh8 seed42 | 6 /21 | .699074 | .270939 |
| Tanh8 seed97, selected | 2 /17 | .716369 | .393230 |

Selected minimum .55 and margin 0. The independently calibrated lexical baseline uses .925 and margin 0. The checkpoint binds the exact article content, identity, feature version, status and edition metadata through a corpus fingerprint. A changed corpus fails closed until deliberately versioned/re-evaluated.

Articles and queries are bounded: 128 articles maximum, 2,048 UTF-16 units/article, 512/query, 20 candidate quotes and at most 10 returned topics. Original quote text and offsets are retained. Query features are computed once per ranking request; title/passages are cached from documentation only. The synchronized passage cache has a strict cap of three entries per article, including concurrent requests and public feature probes. Queries are not retained in that cache. There is no service, cloud inference or Android framework dependency in the runtime.

## Initial evaluation, including regressions

The identical candidate pool and separately calibrated lexical baseline make these a local authored comparison, not a public benchmark.

| Metric | Lexical baseline | Raw learned reranker |
|---|---:|---:|
| Top-1 correct /36 supported | 27 (75%) | 25 (69.44%) |
| Top-3 contains correct topic /36 | 31 (86.11%) | 32 (88.89%) |
| MRR | .815586 | .794907 |
| Candidate recall | 34/36 | 34/36 |
| Accepted correct | 9 | 11 |
| Accepted wrong, all 48 | 1 | 4 |
| Unsupported refused /12 | 12 | 9 |

By language, raw top-1 is English 10/12 versus baseline 9/12; Arabic 6/12 versus 8/12; mixed 9/12 versus 10/12. This is mixed progress and a failed raw quality gate. Higher top-3 recall does not compensate for more accepted wrong references.

The exported default create method adds a deterministic veto: the neural policy must accept, the separately calibrated baseline must accept, and both must select the same topic. The raw method remains available only for explicit experiments. A guard can remove a proposal but cannot upgrade a baseline refusal or switch its accepted topic. This property holds without knowing the true answer; it does not make the baseline correct.

On the now observed 48 initial-test probes, the guard accepted **8**, all 8 correctly labelled, and refused all 12 unsupported questions. It also refused 28/36 supported questions. The lexical control accepted 9 correct and one wrong. This is a precision/coverage tradeoff on a tiny authored sample. The guard was added after seeing the raw failure, so no fresh-evaluation claim is made. Independent labels, a fresh reserved challenge, useful coverage and device measurements are required before activation.

## Validation and reproducibility

Locally passed on the available JVM with Java 8 source/target compilation:

- 89 neural-core JUnit tests, including nine new runtime tests for bounded/immutable source quotes, article/checkpoint binding, finite features, baseline-veto behavior, foreign evidence, concurrent deterministic inference and cache bounds.
- Five support data guards and the existing three documentation export guards.
- Repeated complete four-fit runs with identical selected weights, report measurements and 234-row exported/committed checkpoint parity.
- All 234 probes preserve exact article IDs, quote offsets/text and scores. All guarded accepted topics remain baseline-accepted topics.
- Flavor boundaries, project language/catalog, documentation links, shell syntax and workflow parsing.

The descriptive host benchmark records eight rotating batch means per mode, 32 requests/batch and 64 warmups/mode. Raw samples and median batch means are checked in separately. These are host end-to-end retrieval samples including lexical search, feature construction and ranking, not phone timings, peak heap or battery measurements. No improvement over an uncached implementation is claimed.

The CI job refits under strict Java 8 API checks, verifies frozen documentation and query provenance, compares generated/committed checkpoints, validates both raw and guarded reports, and uploads failed quality evidence. A green integrity build means the experiment reproduces, not that its model is production eligible. New specialist ART/physical-phone execution has not been performed locally; normal Android builds and API 30 UI checks are reported independently.

Reproduce:

```sh
python3 scripts/test_product_support.py
bash scripts/train_product_support.sh
./gradlew :neural-core:test
```

A constrained-host fallback uses --source-target-only. Generated checkpoints are stored outside src/main and are not added to the normal application dependency graph. P0/P2/P5/P6 completion and mutation-recovery rules, flavor permissions, provider routing and ordinary chat behavior are unchanged.

The next useful improvement is independently reviewed support/unsupported labels and more training topics, followed by a separately frozen evaluation. Do not repeatedly optimize this observed 48-question sample or call a small lexical network a general language model.
