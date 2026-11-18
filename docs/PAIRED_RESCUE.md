# Topic-held-out selective recovery

The v13 experiment compares training only on first-stage refusals with training on every first reference. Both arms use v12's six logistic coefficients and optimizer. The target is a first reference that actually answers the question. All arms decide only on refusals at inference. Inputs include the retrieval score, coverage, score gap and frozen answerability signal.

The 108 new questions span 18 topics. Each topic has a supported question and a closely related unsupported request in English, Egyptian Arabic and mixed language. Mixed examples substitute a product term into the Arabic wording. The split by topic (8/4/6) applies to the new selector. The upstream models already know these product topics. Source documents and existing labels were not independently reviewed. The result measures FAQ topic selection and acceptance; it is not a general tool-use benchmark.

| Policy | Added correct / wrong on DEV 24 | Prior 2,052 | Held-out 36 | Both sides correct on held-out pairs |
|---|---:|---:|---:|---:|
| Frozen v10 | 0 / 0 | 0 / 0 | 0 / 0 | 4 / 18 |
| v12 retrieval only | 1 / 0 | 81 / 15 | 4 / 1 | 7 / 18 |
| v12 logistic | 1 / 1 | 24 / 1 | 0 / 0 | 4 / 18 |
| v13 refused training | 1 / 0 | 0 / 0 | 0 / 0 | 4 / 18 |
| v13 all-hit training (selected) | 2 / 0 | 15 / 1 | 0 / 0 | 4 / 18 |

The selected arm fit 48 rows with 21 correct first references. Of the 28 eligible held-out refusals, 11 contained a correct first reference, including four Arabic and five mixed. All eleven remained refused. v12 retrieval recovered one Arabic and one mixed answer and two English answers, at the cost of one incorrect English answer. Final selected counts remain 6 correct and 2 wrong. No model promotion.

A secondary retrospective audit rotates the held-out topic over all 18 groups. Each fold refits both arms on 13 topics and selects its arm and threshold using four other topics. Across 108 out-of-fold decisions this recovered 6 correct and added 4 wrong, including 3 unsupported. The rotations share training data and are not 18 independent replications.

Run `bash scripts/train_topic_rerank.sh` to replay all prior gates, refit and compare the v13 checkpoint and reproduce all 2,160 policy decisions in Java. The Java check uses exported runtime features on the host. It does not measure inference latency on a phone.
