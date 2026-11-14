# Pairwise documentation support training

## Measured result and release boundary

Four real fits replace independent pair classification with a query-balanced ranking objective. The selected **experimental candidate** is a 13-parameter linear head (52 raw FP32 weight bytes), compared with v1's 113-parameter head. The complete retriever still includes the fixed FAQ corpus, lexical index, feature/cache structures and objects. Weight bytes are not whole-application memory or evidence of phone speed.

On 117 newly authored wordings, the raw candidate accepts 33 correctly labelled references and three wrong ones, versus v1 raw's 23 correct and four wrong. It improves raw top-1 from 49/87 to 52/87, but the lexical control still reaches 55/87. The proposed guarded candidate also introduces two wrong acceptances versus the frozen v1 guard's zero. It is therefore **not promoted**. The exported default create method deliberately retains the prior v1 guard; candidate weights require explicitly named experimental constructors. Production eligibility stays false.

No application runtime, permission, provider, Room schema, P0/P2/P5/P6 authority or existing intent-routing checkpoint changes in this experiment. The earlier history title/recovery review fixes are handled separately in PR261. The new learned weights remain outside the normal APK.

## Data and evaluation ownership

The same 58 reviewed English/Arabic FAQ documents are used. Training, development and calibration retain the original 114/36/36 v1 query partitions. The observed 48-question v1 test remains a regression set and never enters fitting, selection or calibration.

A separate 117-row challenge was committed before the first pairwise fit: one English/Arabic/mixed wording for each of 29 topics, plus ten unsupported families with three translations each. That is 87 supported and 30 unsupported questions. Validation pins the file hash, rejects normalized overlaps with all v1 questions and canonical FAQ questions, verifies language/topic coverage, groups translations and rejects false independent-label/readiness claims.

This is a new authored-wording challenge against a fixed, fully searchable corpus. It includes previously trained topics; it is **not semantic OOD, unseen-document evaluation, independently reviewed labels or user traffic**. Its first scores were produced after candidate weights and calibrated policies were exported. Subsequent runs reproduced those fixed results; the challenge is now observed and must not be reused as unseen evaluation.

Only reviewed documentation-derived query/reference pairs enter training. Source code, private chats, credentials, databases and logs do not. The provenance manifest hashes source files to bind implementation identity; it does not turn source code into training text.

## Objective and controlled comparison

The pairwise component follows the probabilistic ranking idea in [RankNet's original paper](https://www.microsoft.com/en-us/research/wp-content/uploads/2005/08/icml_ranking.pdf). This is a small adapted linear objective, not a reproduction of that paper's benchmarks or a claim of a modern semantic language model.

For each training query, correct-topic hits are compared with up to eight wrong-topic candidates from the existing lexical pool. The loss is .75 times the average pairwise logistic loss plus .25 times balanced binary relevance loss. Per-query normalization prevents long candidate lists from dominating. Unsupported training queries contribute negative relevance loss. No expected topic, query identity or split enters runtime features.

The 12 lexical input features remain frozen. A linear logit is initialized around a lexical anchor: coefficient zero is 5, other coefficients and bias zero. Two anchor penalties (.02/.1) and seeds42/97 produce exactly four fits. AdamW uses batch16 queries, learning rate .01, norm clipping5, decay .0001 on displacement from the anchor, maximum80 epochs and patience12.

Checkpoint selection uses development top-1, MRR and NLL with a per-language top-1 floor against the lexical control. Final model selection includes the zero-parameter lexical control, preferred when a learned candidate offers no ranking gain. Calibration alone chooses score/margin cutoffs using the existing accepted-correct minus three-times-accepted-wrong utility. The fresh challenge and old observed test are scored only after export.

| Anchor /seed | Selected epoch /epochs run | Development top-1 /24 | Development MRR |
|---|---:|---:|---:|
| .02 /42 | 5 /17 | 13 | .671843 |
| .02 /97, selected | 3 /15 | 13 | .675694 |
| .1 /42 | 27 /39 | 12 | .650347 |
| .1 /97 | 9 /21 | 12 | .650347 |
| Lexical control | No fit | 12 | .642824 |

Selected candidate minimum .875, margin0. Lexical minimum .925, margin0. These policies are not retuned after new-wording scoring. The default-retention wrapper is rollout protection, not a second trained model or a fresh-evaluation gain.

## Full results, including failed gates

All five modes use the same eligible candidate pool and original source quotations.

| New authored wordings | Lexical | V1 raw | V2 raw candidate | V1 guard /retained default | V2 candidate guard |
|---|---:|---:|---:|---:|---:|
| Top-1 correct /87 | 55 | 49 | 52 | 49 | 52 |
| Correct topic in top-3 /87 | 72 | 66 | 68 | 66 | 68 |
| MRR | .732742 | .681349 | .703385 | .681349 | .703385 |
| Accepted correct | 27 | 23 | 33 | 18 | 27 |
| Accepted wrong /117 | 2 | 4 | 3 | 0 | 2 |
| Unsupported refused /30 | 30 | 29 | 30 | 30 | 30 |

The retained default has v1's ranking and selective acceptance. Candidate scoring improves over v1 raw but remains below lexical ranking. The candidate guard's higher coverage does not justify its added wrong acceptances.

| Raw top-1 by language /29 supported each | Lexical | V1 | V2 candidate |
|---|---:|---:|---:|
| English | 21 | 19 | 20 |
| Arabic | 14 | 10 | 14 |
| Mixed | 20 | 20 | 18 |

Mixed-language ranking still regresses. The old observed 48-question regression set gives raw v2 13 accepted-correct/one wrong, versus v1's11/four; it is not a fresh test. Individual previously accepted-correct losses and candidate-guard new wrong IDs remain in retention.json. Aggregate gains do not erase those losses.

The quality/provenance report records failed lexical-top1 and guarded-error gates, missing independent labels and absent physical-device verification. No model activation follows a green integrity build.

## Verification and Android integration

Locally verified with the available JVM and Java8 source/target compilation:

- Six data guards for pinned new questions, translation/topic/language coverage, observed duplicates, split contamination and false review claims.
- An independent central finite-difference check of all13 objective coordinates on a supported and an unsupported real training query: 26 coordinates. Actual fitting rejects non-training rows; checkpoint selection rejects non-development rows.
- Repeated four-fit reproduction, exported/committed source and 1,755 prediction comparisons across351 questions ×five modes. Article identities, offsets and decisions match exactly; host probabilities are serialized to nine decimals. Default output remains identical to frozen v1 on all351 probes.
- Original quote preservation, lexical guard non-upgrade checks and explicit individual regression reports.
- The actual Android-packaged support contract runs on the host with 1,755 predictions, 351 default checks and five corrupt-fixture rejections. Existing original/public/residual/utility/prototype/trust host contracts remain passing.

The opt-in neural Android test APK receives the two experimental checkpoint factories and reviewed documentation/probes in generated androidTest sources/assets only. Its new instrumentation test checks the same contract on ART and writes a run-ID-bound report. CI requires exactly58 documents,351 questions,1,755 predictions and351 retained-default checks from the current run; a stale/missing report fails. ART probability tolerance is1e-6 while source identities, offsets and decisions stay exact. Fixture inputs include the new scripts, documentation and corpus so Gradle cannot reuse stale generated inputs.

Strict Java8 API compilation and new API30 ART execution are delegated to CI and reported separately. No physical Infinix, peak heap, latency or battery benchmark was performed. Thirteen weights and removal of tanh reduce head arithmetic, but do not establish end-to-end mobile speed.

## Reproduce and next evaluation

```sh
python3 scripts/test_ranked_support.py
bash scripts/train_ranked_support.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-neural-support
./gradlew :neural-core:test
```

The constrained-host fallback is --source-target-only. Files are under fixtures/product-support-v2, checkpoints/product-support-v2, the two RankedSupport training classes and matching scripts/smokes. The runtime reuse remains ProductSupportModel, OmniRamLite and TinyNeuralRuntime.

Next work should address the mixed-language representation/candidate limitations using training/development evidence and independent answerability review, with a separately reserved future evaluation. This revision does not claim complete product knowledge, universal troubleshooting, trained title generation, P9 completion or production readiness.
