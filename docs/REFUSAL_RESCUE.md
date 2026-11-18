# Recovering refused Omni support answers

v11 located 14 refused questions with a correct first reference in one authored set. v12 keeps this reference fixed and compares three ways to decide whether a first-stage refusal should answer. Explicit v10 vetoes stay final. The baseline and all comparisons use identical query IDs. The selected retrieval-only rule costs one score comparison; the alternative logistic rule uses six coefficients and one frozen query/reference score.

## Research decisions

- Selective question answering under domain shift evaluates how often a system can answer at a given accuracy. Its learned calibrator motivates combining evidence signals. Omni fits its small calibrator on first-stage refusals only. The authors studied out-of-domain calibration examples. This experiment only has authored Omni examples. [Kamath et al.](https://aclanthology.org/2020.acl-main.503/)
- SelectiveNet jointly learns selection and prediction. Freezing retrieval here allows a controlled comparison with its existing first choice and isolates changes in acceptance. [Geifman and El-Yaniv](https://proceedings.mlr.press/v97/geifman19a)
- SQuAD 2.0 uses plausible questions that a passage cannot answer. The six new topic groups each include a supported question and a related unsupported request in three languages. Translations share semantics, so 36 questions represent six topic groups. [Rajpurkar et al.](https://aclanthology.org/P18-2124/)
- MS MARCO MiniLM models score passage relevance. The English ranking benchmarks do not measure whether a bilingual Omni FAQ answers a user's specific question. v12 compares a retrieval score against the existing bilingual answerability score directly. [Model card](https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2)
- Learn then Test separates model fitting from statistical testing and accounts for multiple candidate policies. v12 selects both arm and threshold on DEV. Zero incremental errors there is a selection constraint. A statistical risk claim would require appropriate independent calibration data and testing. [Paper](https://arxiv.org/abs/2110.01052) and [reference code](https://github.com/aangelopoulos/ltt).

## Paired outcomes

Cells give additional correct / additional wrong answers over frozen v10. Historical totals include fitting and development rows. The new 36 were reserved before fitting.

| Recovery rule | Development 135 | Historical 2,016 | New 36 |
|---|---:|---:|---:|
| Retrieval threshold (selected) | 6 / 0 | 80 / 15 | 1 / 0 |
| Pair score | 0 / 0 | 0 / 0 | 0 / 0 |
| Logistic combination | 3 / 0 | 24 / 1 | 0 / 0 |

Seven of the selected rule's 15 new historical errors answer unsupported queries. Eight select a wrong reference for a supported question. The new cohort moves from 9 correct and 3 wrong to 10 correct and 3 wrong. On the previous v11 cohort, the same rule gains one correct and adds one wrong. The logistic alternative's historical trade-off merits independent evaluation. These findings do not justify enabling automatic recovery in the app. No new physical-device latency or memory measurements.

Reproduce with `bash scripts/train_topic_rerank.sh`. The script checks the v11 replay, refits the v12 coefficients and thresholds, and verifies the committed report, including error IDs and language breakdowns.
