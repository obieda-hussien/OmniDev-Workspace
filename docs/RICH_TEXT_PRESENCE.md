# Fitted lexical representation and fused intent presence

This experiment builds on PR #253 at `905e1cc603024e0988c1c3bafdc38121833dafe2`.
It improves the observed retention/refusal tradeoff without changing the original
domain classifier. It is not a pretrained semantic encoder, fastText reproduction,
Jev equivalent or production promotion. Normal application dependencies, permissions,
Room, providers, chat paths and P0/P2/P5/P6 remain unchanged.

## Representation and training

`RichTextEncoder` combines the original 256 hash features with up to 768 explicit
word and adjacent-word-pair counts in a fixed 1,024-dimensional
vector. The hash and fitted-vocabulary branches are normalized separately with
`sqrt(0.5)` scale. When no fitted terms occur, that branch stays zero; the unchanged
hash backoff still covers the bounded full text. Words beyond length 64 break pair
adjacency and the fitted branch considers at most 256 words. All text retains the
existing 2,048-character bound and normalization. Vocabulary terms are immutable,
sorted and bounded; a SHA-256 of version, ordered terms and FP32 IDFs binds the
checkpoint to its actual fitted encoder rather than dimensions alone.

Only the 796 fitting rows from the frozen bilingual corpus enter vocabulary fitting.
Document frequency is counted per original scenario group, unioning its paraphrases.
Terms appearing in at least two groups are ranked by maximum domain-presence chi-square.
Up to 192 Arabic and 192 non-Arabic terms reserve space before filling to 768; ties
are lexical. IDF is `1 + log((groups + 1)/(DF + 1))`. Neither vocabulary, IDF nor feature
selection reads development, calibration, observed regressions or the reserved new test.
This is supervised lexical feature selection; it does not supply pretrained meaning.

Five binary domain-presence heads use the previous multilabel targets, two seeds per
head and the existing bounded early stopping. Architecture stays linear. Development
macro NLL chooses the seed/checkpoint; calibration chooses the temperature. Unlike the
previous 5% development coverage budget, every configuration losing even one prior
known development choice is rejected. A fixed threshold/policy grid maximizes refusal
recall only among feasible development configurations. Selection chose threshold 0.9,
multi-domain-only refusal. Development retention is a constraint, not a future guarantee.

The desktop example width limit rises to 1,024 with a 20,000-trained-parameter cap.
All previously valid <=256-dimensional shapes remain valid. The runtime's existing
100,000-parameter limit is unchanged. The smaller training cap bounds generated Java
checkpoint classes as widths grow. Legacy fitting update order, default encoder and
saved model contracts remain compatible and are checked by the original training suite.

## Fused execution and speed boundaries

Each calibrated binary linear classifier can be expressed as a difference of its
two logit weight rows. Concatenating five such rows gives one 1,024-by-5 sigmoid
layer: **5,125 FP32 parameters**, versus 10,250 for five separate two-output heads.
This reduces the presence-layer parameter count by half, not the full system size.
Together with the original domain/refusal heads it uses 13,468 weight parameters,
plus vocabulary/IDFs and object/storage overhead. This is not a measured peak-heap claim.

Normalization is shared, rich encoding is skipped when the prior already refuses,
and absent vocabulary slots skip `log1p(0)` and zero normalization divisions. Sparse
first-layer inference is opt-in through the existing immutable runtime. Checkpoint
arrays keep the existing chunked Java exporter; no Python/JNI is needed for inference.

FP32 fusion changes the numerical expression. Its maximum observed probability
difference from independent calibrated heads is below `2e-6`; all 1,665 old+additional
observations also retain identical typed fused/unfused decisions. This is observed
parity, not universal equivalence for every future input near a cutoff.

Host measurements include normalization, encoding, prior, refusal inference and score
consumption. Four modes rotate over eight warmed batches, each with 64 Arabic, 64 English
and 64 mixed fitting inputs. Raw batch means and medians are retained. One local run
measured about 56us fused versus 77us equivalent unfused, while the simpler prior was
38us and previous smaller presence adapter 43us. Thus fusion reduces this representation's
cost, but the richer path is still slower than the simpler baselines on that host.
No speed assertion, phone speed, individual-call percentile or app latency claim follows.

## Data and observed results

The prior 1,551 rows are already-observed regressions. Before fitting, 114 previously
unused English upstream test utterances were reserved from positions 20-29 of the
eight mapped CLINC intents and four already category-held-out topics. Exact old texts,
IDs and challenges are excluded before any scores are inspected. Six utterances were
excluded; 34 supported and 80 unsupported remain. Source revision/license, exclusions,
dataset/protocol hashes and deterministic import recipe are retained under
[`fixtures/rich-text`](../neural-core/fixtures/rich-text/NOTICE.md). The source is public
English CLINC, not new Arabic data or the standard CLINC150 benchmark.

| Observed metric | Prior refusal gate | Previous presence | Rich fused |
| --- | ---: | ---: | ---: |
| Cross-domain composed refusals | 28/120 | 58/120 | 44/120 |
| Regression known choices retained | 90 | 85 | 90 |
| Regression accepted correct | 85 | 80 | 85 |
| Regression accepted errors / accepted | 103/188 | 73/153 | 87/172 |
| Original authored accepted correct / accepted | 25/28 | 24/27 | 25/28 |
| Same-domain composed accepted correct | 30/30 | 26/30 | 30/30 |
| Additional English accepted correct / known choices | 25/28 | 24/27 | 25/28 |
| Additional English required refusals | 49/80 | 49/80 | 49/80 |
| Prior topic-held-out English refusals | 25/40 | 25/40 | 25/40 |

The rich model rejects 16 additional composed negatives over the prior while restoring
the five correct known cases lost by the previous presence model. It rejects fewer
mixed requests than that more aggressive model. It retains all observed prior known
choices/correctness in these groups; that is not a general false-refusal guarantee.
Unknown-topic performance and old ambiguity challenges do not improve. The additional
English set gives no improvement over the prior and cannot support Arabic accuracy.
Synthetic composition templates still limit generalization. The fixed extra English
set was first evaluated after export; subsequent unchanged-model reproduction is not
another independent test. **Production eligibility remains false.**

## Reproduction and verification

```bash
python3 scripts/prepare_fresh_intent_eval.py --verify
python3 scripts/test_rich_text_data.py
bash scripts/train_rich_text.sh
# Compile/check existing local artifacts without fitting again:
bash scripts/train_rich_text.sh --verify-artifacts
```

The script compiles actual training/runtime classes, fits ten candidates, exports model
and vocabulary, and verifies metadata, numeric scores and decisions against in-memory
observations on all 1,665 inputs. It compares the committed checkpoints as well. Source,
protocol and corpus hashes identify artifacts; paired-vocabulary mismatches are rejected.
New tests cover order-sensitive features, unchanged hash backoff, bounds, copied arrays,
foreign vocabulary pairing, prior refusal preservation and wider-training/fusion limits.
The reserved-test guards reject leaked texts/IDs, changed remappings and upstream train IDs.

The standalone test APK includes these committed checkpoints only in androidTest. Its
139 public regressions gain rich feature hashes, sigmoid-score tolerance checks,
typed decisions and exact dense/sparse checks. Six rotated Android timing modes include
rich dense/sparse paths. Runner report freshness and cleanup protections stay intact.
The Android job must actually pass before claiming ART verification of this revision.
The full app still does not depend on neural-core or these checkpoints. No physical
Android device, memory or battery benchmark has been performed.

Local source/target-8 fitting/export/reload and legacy checks were run; that fallback
is not strict Java-8 API or Android compilation. CI separately runs strict release-8
and JUnit checks, rich retraining, standalone API 30 validation and normal app checks.
Pending CI is not reported as passed. Further work needs reviewed bilingual evaluation,
stronger representations/unknown-topic rejection and physical-device measurements.
