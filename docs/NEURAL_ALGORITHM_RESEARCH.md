# Algorithm research for Omni's lightweight decision specialists

Sources inspected on 2026-11-13. These are research candidates, not implemented
algorithms or reproduced performance results unless explicitly stated below.
Our actual [bilingual experiment](BILINGUAL_REFUSAL_TRAINING.md) remains separate.

## Findings and boundaries

| Approach | Primary source | Useful property | Cost or evidence boundary for Omni |
| --- | --- | --- | --- |
| fastText | [Joulin et al., EACL 2017](https://aclanthology.org/E17-2068/), [author implementation](https://github.com/facebookresearch/fastText) | Compact text classification with learned representations and word n-gram features | Our fixed hashed character/word encoder is not fastText or a pretrained semantic encoder. Word-order/embedding ablations need retraining and explicit feature versions. No direct benchmark transfer. |
| ProtoNN / Bonsai | [Gupta et al., ICML 2017](https://proceedings.mlr.press/v70/gupta17a.html), [Microsoft EdgeML](https://github.com/microsoft/EdgeML) | Learned compressed prototypes or projected shallow trees designed for tiny runtimes | Strong candidates for a memory-bounded baseline. Simple centroid lookup is not a faithful ProtoNN implementation: the original jointly learns projection, prototypes and labels. |
| SetFit | [Tunstall et al., 2022](https://arxiv.org/abs/2209.11055), [author implementation](https://github.com/huggingface/setfit) | Contrastive fine-tuning of a pretrained sentence encoder, then a small classifier | Meaningful multilingual features require a suitable multilingual encoder. The small classifier head does not account for the encoder's weights, latency or memory. Teacher/distillation is a future experiment, not performed here. |
| SNGP | [Liu et al., NeurIPS 2020](https://proceedings.neurips.cc/paper/2020/hash/543e83748234f7cbab21aa0ade66565f-Abstract.html) | Distance-aware uncertainty via spectral normalization and a Gaussian-process output approximation | Addresses confident unknown inputs, but requires the actual representation/training changes and overhead measurement. A distance threshold alone is not SNGP or an OOD guarantee. |
| Energy-based OOD detection | [Liu et al., 2020](https://arxiv.org/abs/2010.03759) | Score or train the logit energy rather than trusting only normalized softmax | Relatively cheap scoring is worth an ablation, but our bounded hashed representation and shifted language data may still fail. Threshold selection must be separate from test evaluation. |
| Multi-cluster boundaries | [Xu et al., July 2026 preprint](https://arxiv.org/abs/2607.07974) | Reject outside local known-intent regions instead of forcing a closed-set label | Its MiniLM embedding and full router/expert pipeline must be counted; it is not evidence that an entire system fits in a few thousand parameters. Primary abstract was retrieved by search; the direct page fetch failed. Results have not been reproduced. |
| GLAD | [Duan et al., 2026](https://www.sciencedirect.com/science/article/pii/S0950705126006751) | Prototype graphs, contrastive objectives and selective model checking | The reported combination is a different system. The linked public code endpoint returned 404 when inspected, so implementation/reproducibility was not verified. No adoption or paper benchmark claim. |
| Selective semantic routing | [Sun and Sang, EMNLP 2026](https://aclanthology.org/2026.emnlp-industry.257/) | Retrieve candidate exemplars and escalate ambiguous inputs under a budget | Their comparisons explicitly show no single routing policy dominates all points. Cloud/model escalation must preserve provider abstraction, permissions and cost limits; it is optional, not a mandatory backend. |
| Conformal prediction | [Barber et al., 2022](https://arxiv.org/abs/2202.13415), [NLP survey](https://doi.org/10.1162/tacl_a_00715) | Explicit prediction sets/coverage under stated assumptions | Standard coverage requires exchangeability and appropriate calibration separation. It is not a blanket guarantee against arbitrary unknown topics or domain shift. Current synthetic/grouped data do not establish those assumptions. |

Jev's [official introduction](https://typesafe.ai/blog/introducing-system-one-models-and-jev)
describes typed probabilistic outputs and Reinforcement Learning for Calibrated Decisions.
That interface is a useful design reference. The announcement is not a complete
reproducible local weight/training specification. Our supervised heads, temperature
selection and refusal cascade are not Jev, RLCD or equivalent frontier intelligence.

## Engineering decision

The current experiment found that expanding training and hidden width alone can hurt
development generalization. Retaining an immutable best development snapshot is now
implemented without changing the old trainer path. The original domain head stays
frozen. A two-head refusal cascade is evaluated explicitly as a follow-up on observed
regressions, not as an independent test or any algorithm above.

The next comparison should isolate the representation bottleneck: a compact word
n-gram baseline and a properly bounded prototype/distance baseline, followed by a
multilingual contrastive teacher/distillation experiment only if its complete encoder
cost and data provenance are acceptable. This ordering is an engineering inference
from our local failures and the cited methods, not a proven optimum.

Each new family needs an unchanged independent test protocol, source/license records,
Arabic/mixed accepted-correctness and false-refusal measurements, unknown-topic tests,
and host/ART/physical-device timing and memory evidence. No learned score authorizes a
tool, changes a flavor's permissions, or replaces P0/P2/P5/P6. No speculative algorithm
is enabled in the production APK.
