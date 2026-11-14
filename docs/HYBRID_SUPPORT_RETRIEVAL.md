# Experimental bilingual field fusion (v3)

This is documentation retrieval for possible larger-model context, not a generative model,
chat-title model, execution authorizer or completion verifier. It is not enabled in the normal
APK. P0/P2/P5/P6, edition permissions and provider routing are unchanged. History titles remain
the separate deterministic metadata feature described in [history metadata](CHAT_HISTORY_METADATA.md).

## Why change the representation

The v1/v2 heads often saw weak candidates before scoring began. V3 pairs the existing English
and Arabic FAQ translations by their stable topic, and compares four BM25 views: raw bilingual
body, raw bilingual question, lightly normalized bilingual body and lightly normalized question.
Queries and documents use the same normalization. This is lexical retrieval, without embeddings,
semantic understanding, stemming models, new knowledge or source-code ingestion.

Three primary references motivated alternatives rather than guaranteeing better results:

- [Cormack et al., SIGIR 2009](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf):
  reciprocal rank fusion; our zero-fit control uses k=60 across the four field rankings.
- [Elastic's linear retriever discussion](https://www.elastic.co/search-labs/blog/linear-retriever-hybrid-search):
  normalized weighted score fusion. Our query-maximum normalization differs from its MinMax example.
- [Arabic normalization evaluation](https://www.mdpi.com/2073-8994/18/10/1703):
  consistency across query/document pipelines matters in that paper's setup. We use our own light
  analyzer, not its Farasa pipeline or reported benchmark results.

Arabic processing removes diacritics and selected spelling variants, with guarded article/clitic
stripping and a small stoplist. The negations Arabic "la"/"mish" and service names such as WhatsApp are retained; the ambiguous Arabic "ma" remains a stopword, a known limitation. This is
an intentionally limited analyzer; English and mixed-language regressions remain below.

## Runtime and training contracts

`HybridSupportRetriever` indexes up to 128 topics, with at most two original references per topic.
Each `Hit` returns original `ProductSupportModel.Article` objects separately, including status
and edition labels. Concatenated field text is internal indexing material, never a fabricated
quotation. A reference being retrieved does not grant the referenced capability.

Eight features comprise four normalized BM25 scores, light question/body query coverage,
question trigram Dice and IDF-weighted body coverage. Immutable indexes are shared across queries;
query rows are local, copied defensively, bounded and not retained in a history cache. The existing
lexical candidates remain a backstop. Query length is bounded at 512 characters and output at ten hits.
These structural bounds do not establish measured peak heap or phone latency.

The experiment compares LEGACY, TITLE, RRF and UNIFORM controls against four actual convex
pairwise logistic fits: learning rates .05/.15, seeds 42/97, query batches of 16, at most eight hard
negatives per query, gradient cap 5, L2 .01 toward uniform and nonnegative simplex projection.
Maximum 80 epochs, patience 10. Training uses the original 114 TRAIN queries only. Original
36 DEV queries select top-1 then MRR, subject to a per-language floor against lexical DEV.
Original 36 CAL queries select minimum score, score margin and coverage by correct minus three
incorrect acceptances. Neither fresh evaluation set fits weights or calibrates thresholds.

The selected fit (seed 97, learning rate .15) uses epoch 5, with 15 epochs executed. DEV top-1 is
16/24, versus LEGACY 12, TITLE 12, RRF 15 and UNIFORM 14. Eight fitted coefficients are
`[.32666817, .07891732, .41298637, .18142816, 0, 0, 0, 0]`.
The export stores those plus a fixed zero bias: nine FP32 values, **36 raw bytes**, excluding Java
source/class overhead, documents, indexes and the rest of the runtime. Only eight coefficients
are fitted. This is a small learned linear fusion head, not a general-purpose intelligence model.

Selected minimum and margin are both zero; coverage is .5. Scores are bounded relevance values,
not calibrated probabilities. In this selection, abstention depends mainly on query coverage.
Do not interpret `accepted` as task completion or proof the documentation answers every requirement.
`TrainedHybridSupport.createCandidate` is explicit experimental retrieval;
`retainedDefault` returns the frozen v1 guarded behavior, unchanged.

## Frozen authored evaluation

`fixtures/hybrid-support-v3/protocol.json` pins 108 new authored queries before local fitting:
72 supported (36 compound, 36 single) and 36 unsupported. English, Arabic and mixed translations
are grouped; all 29 supported topics appear in each language. Primary labels have relevance 2,
secondary labels 1. The original 58 FAQ references stay searchable, including topics seen in
training. This is **not** unseen-document/OOD evaluation, independent human review or real user traffic.
The test has now been observed and must not be repeatedly tuned against.

| New authored evaluation | Lexical | Frozen v1 guard | Raw v2 | Hybrid v3 |
|---|---:|---:|---:|---:|
| First result correct / 72 | 55 | 43 | 52 | 52 |
| MRR | .836938 | .730589 | .810780 | .820403 |
| All primary requirements in first 3 / 72 | 48 | 46 | 50 | 55 |
| nDCG@3 | .741441 | .655347 | .733793 | .763046 |
| Accepted correct | 27 | 13 | 31 | 12 |
| Accepted incorrect | 2 | 0 | 5 | 0 |
| Unsupported refused / 36 | 36 | 36 | 36 | 36 |

| Language (24 supported each) | Lexical top-1 | Hybrid top-1 | Lexical all-primary@3 | Hybrid all-primary@3 |
|---|---:|---:|---:|---:|
| English | 22 | 18 | 20 | 21 |
| Arabic | 11 | 14 | 10 | 16 |
| Mixed | 22 | 20 | 18 | 18 |

Arabic and compound reference coverage improve, but overall and English first-result accuracy
regress. Hybrid selective accepted-correct coverage is below the retained guard (12 vs 13), even
with no observed false acceptances on this small set. Zero observed errors is not a population guarantee.
The 165 already observed old-test/v2 probes are regression evidence only: hybrid top-1 84/123
versus lexical 82/123 and retained guard 74/123. They are not new independent evaluation.

Across all 459 train/dev/cal/old/fresh queries, the compiled checkpoint matches predictions and
the retained default exactly. Original-reference checks total 8,392. The candidate nevertheless
loses ten previously accepted-correct results across those combined splits; their IDs remain in
`parity-retention.json`. Do not replace the default based on aggregate gains.

## Reproduction and verification

```sh
python3 scripts/test_hybrid_support.py
bash scripts/train_hybrid_support.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-neural-fixtures
./gradlew :neural-core:test
```

Use JDK 17 for strict Java 8 API compilation. The optional `--source-target-only` script flag is a
local compiler fallback, not proof of strict API compatibility. The training script reproduces
all four fits, checkpoint bytes, 459 query predictions, exact default retention and pinned source
hashes. The training smoke checks sixteen actual finite-difference gradient coordinates, simplex
projection and partition rejection. Data guards reject contamination and false review/readiness claims.
JUnit covers symmetric normalization, original immutable references, malformed weights/corpus/version,
bounds, abstention and concurrent deterministic queries.

The standalone Android test APK packages this separate candidate alongside the older checkpoints;
its pure Java contract verifies the same 459 predictions/defaults and rejects five corrupt fixtures
on the host. API 30 instrumentation writes `hybrid-correctness.json`, checked against the current run
ID by the CI harness. Host execution of that contract is not ART/device execution. The normal APK
does not package or activate this experiment. Android CI and physical-device evidence are separate.

Reports live in `neural-core/checkpoints/hybrid-support-v3/`. Quality/provenance records explicitly
keep `production_eligible`, independent human labels and physical-device testing false. Do not
claim phone speed, battery use, peak heap or Infinix X689 performance without a measurement on that device.

Next: independently review Arabic/mixed/compound labels and build a genuinely new, scenario-grouped
challenge before further representation changes. Evaluate selective coverage and English regression
alongside primary-reference coverage. P7 authorization, P8 postcondition verification and P10 desktop
evidence are separate deterministic contracts; a retrieval score must never replace them.

Local verification on 2026-11-14: 95 actual JUnit tests and 17 support data guards passed;
four-fit reproduction, 16 gradient coordinates, 459 compiled predictions/default checks and
all packaged host contracts passed. This environment used Java 8 source/target with JDK 21.
Strict Java 8 API compilation, Gradle and the new ART instrumentation are delegated to CI;
no physical Android hardware measurements were performed.
