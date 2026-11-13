# Utility-aware expert training and conditional preservation

This changes both the training algorithm and how an experimental expert is trusted. It improves measured proposal/refusal outcomes, but still loses **three previously correct held-out cases**. Both checkpoint reports and the selected source explicitly mark production eligibility false. Neither version enters the normal APK, authorizes tools, changes providers/flavors or weakens P0/P2/P5/P6.

## Why change the method?

The preceding residual experiment improved a likelihood objective but selected zero correction: one global gain could not improve decisions while retaining earlier correct examples. A separate six-class replacement rejected many unsupported requests but damaged older known cases. Keep the existing rich-text plus conservative public-refusal cascade as a frozen teacher and learn a bounded expert with domain-specific trust regions instead.

The implementation uses mini-batch Adam moments and **decoupled** weight decay following [AdamW](https://arxiv.org/abs/1711.05101). A temperature-one soft-target anchor is inspired by [knowledge distillation](https://arxiv.org/abs/1503.02531). This is a custom bounded experiment, not a reproduction of either paper's experiments or a Jev implementation.

## Frozen data and actual training

Use only the existing 2,162 training rows from the pinned authored/bilingual and public MASSIVE corpora. Keep upstream partitions, old scenarios and parallel translation IDs. Balance the 18 language/class strata in round-robin order, yielding 12,456 examples before each seeded shuffle. The fixed rich-text-v1 representation remains 1,024 dimensions; vocabulary and IDF never fit held-out data.

Four unique neural fits: linear (6,150 parameters) or tanh16 (16,502), seeds 42/97. Each uses batch size 32, learning rate .003, beta1=.9, beta2=.999, epsilon1e-8, weight-only decoupled decay .0005, global gradient norm clipping at 1 and at most 100 epochs. Xavier initialization and every shuffle are seeded. Check development language/class-macro raw accuracy every five epochs; tie on lower NLL, retain copied best weights, patience 15. All four selected epoch 5 and ran 20 epochs. Repeat fits are reproducibility checks, not additional independent trained specialists.

True labels receive .02 smoothing. Only on old supported training examples where the teacher's accepted choice equals the true label, mix that target equally with the teacher's calibrated five-domain probabilities extended by zero reject probability. Known teacher mistakes and unsupported false choices are never distilled as truth. Calibration alone selects temperature from .5/.75/1/1.5/2/3/5.

Select the final expert using **actual development policy utility**, not NLL alone. Include the previously committed six-class SGD model as an eligible control under exactly the same policy selection. Report if it wins; there is no assumption that the new optimizer is better.

## Initial policy and follow-up

The runtime keeps every teacher abstention unchanged. An expert has six labels: repository/build/device/knowledge/chat plus `__reject__`. Per teacher domain, fit separate thresholds for rejecting or changing an alternative proposal. Agreement and fallback return the exact original decision; rejection retains its original five probabilities; a switched proposal retains the expert's own six-class distribution. Scores from the two label spaces are never spliced into an invented confidence distribution. These are proposals only, not execution permission or completion evidence.

Initial v1 fit thresholds on development with zero lost earlier-correct known development choices. It selected tanh16/seed 42, temperature 2. On the new reserved data it increased accepted-correct Arabic 8->12/28 and English 7->10/28 with no lost prior-correct cases there. But it lost five old same-domain compositions. Preserve its actual weights/report in `neural-core/checkpoints/utility-expert-v1`.

After those scores were observed, version the separate `rehearsal-protocol.json` **before** follow-up selection. All v2 evaluation sets are therefore observed follow-up regressions, not another independent fresh test. Do not conceal this distinction.

V2 adds two protections:

1. Training rehearsal: earlier-correct supported **training** examples also constrain retention. They never contribute to development utility. Both training and development correct-case retention must hold to make a policy eligible.
2. Domain support: before an expert rejection, require the frozen teacher's existing presence score for its selected domain to be below a learned cutoff. This reuses its immutable network weights; it is not permission or independent verification.

Per domain, enumerate fixed descending reject and switch grids and ascending protective-support grids. Threshold1.1 explicitly disables an action or protection, as documented in the protocol. Maximize language/outcome-balanced correct development decisions, keep conservative ties, and require the exact earlier-correct cases to remain correct. Bound both observation lists at 10,000. Prior abstentions can never be upgraded even if an expert is confident.

## Selected model and measured outcomes

V2 selected the **linear AdamW seed 42** expert, 6,150 parameters, temperature .75. Development balanced policy utility: .749840, compared with .736547 for the fixed SGD control and .728289/.731878 for the larger new experts under the same v2 selector. This is a development comparison, not independent benchmark accuracy. The complete selected teacher+expert path has 20,643 weight parameters, excluding vocabulary/object overhead.

| Observed follow-up set | Teacher | V2 utility policy |
|---|---:|---:|
| Accepted-correct among339 supported held-out requests | 192 | 213 |
| Accepted errors among all1,387 held-out requests | 615 | 379 |
| Unsupported refusals among1,048 requests | 497 | 706 |
| Previously correct held-out cases lost | 0 | **3** |
| Original authored accepted-correct /30 | 25 | 25 |
| Same-domain compositions accepted-correct /30 | 30 | **28** |
| Cross-domain compositions refused /120 | 44 | 71 |
| Prior public Arabic accepted-correct /52 | 14 | 15 |
| Prior public Arabic unsupported refused /228 | 89 | 134 |
| Prior public English accepted-correct /52 | 20 | 26 |
| Prior public English unsupported refused /228 | 97 | 158 |

The three lost individual cases are two same-domain compositions and one previously correct public English request. Gains elsewhere do not erase those regressions. `observed_known_retention_passed:false`, `production_eligible:false`, and `PRODUCTION_ELIGIBLE=false` stay explicit. There is no production activation or claim that P9 is complete.

## Reservation and its limits

Before initial v1 fitting, reserve 136 public utterances in 68 parallel Arabic/English groups, excluding every older source ID and normalized text. Supported positives are 56 utterances (28 per language), volume up/mute only, withheld from unused **upstream TRAIN** groups. They are held out from every one of our fits, but are **not the upstream official test split**. Unsupported requests are 80 unused official-test utterances from four existing remapped topics. Keep the original MASSIVE 1.1 SHA pins and CC BY 4.0 attribution inherited from the public corpus; no source worker metadata is stored.

The v2 reserved-set observations are Arabic accepted-correct 8->11/28, unsupported refusals 19->26/40; English accepted-correct 7->13/28, refusals 21->30/40. No previously correct reserved case is lost. Those are now **observed regressions** because v1 already scored the same utterances. They do not establish independent v2 accuracy, full five-domain coverage, Egyptian dialect quality, or the standard MASSIVE benchmark. Near-paraphrase similarity across distinct source IDs also limits generalization claims.

## Runtime work and performance

`UtilityExpertDecisionModel` shares one normalized feature preparation with its teacher, checks matching vocabulary fingerprints, clones policy arrays and validates all cutoff/score/schema bounds. Existing `RichIntentRefusalModel.classify` retains its previous path without an added preparation-object allocation; only the new adapter requests the prepared context. Disabled domain actions skip expert execution. Reuse the existing domain-presence weights rather than loading another copy. No permanent service or remote inference is added.

Verify the shared path against an otherwise identical repeated-encoding path on all 4,226 observations: probabilities and typed decisions must be exactly equal. One recorded warmed host run measured medians of batch means ~20.8us teacher, ~22.8us shared utility and ~28.6us repeated encoding. The shared implementation reduces work compared with the **same** utility policy, while still costing more than the simpler teacher. Eight raw rotated batches of 192 texts, equal Arabic/English/mixed counts, include encoding/inference/score consumption and show substantial host outliers. No p95, cold-start, peak-heap, Android speed or physical-phone claim is made.

## Reproduction and evidence

```bash
python3 scripts/reserve_utility_eval.py --verify
python3 scripts/test_utility_data.py
bash scripts/train_utility_expert.sh
bash scripts/prepare_neural_android_fixtures.sh build/utility-android-fixtures
```

Defaults use full JDK `--release 8`; the explicitly named local `--source-target-only` fallback is weaker. `--verify-artifacts` checks existing generated outputs without fitting again. Actual committed sources, reports and pins are in `neural-core/checkpoints/utility-expert-v1` and `utility-expert-v2`.

The actual optimizer smoke check covers finite-difference gradients for linear/tanh layers, clipping, decoupled weight-only decay, deterministic learning, copied best snapshots and split leakage. Policy controls exercise training-only rehearsal and support protection rather than a substitute policy. Refit the initial v1 neural checkpoint and compare raw logits over fitting/validation/calibration inputs; the local maximum difference is 0. V2 exported and committed models are checked across 4,226 actual observations for metadata, numeric probabilities within2e-6, exact typed decisions, exact dense/sparse logits and exact shared/repeated-encoding behavior. Every prior refusal remains unchanged.

The standalone Android test APK packages **only v2** plus the existing test checkpoints and attribution. It retains prior 139/699/859 contracts and adds 995 utility probes: expert probabilities, old support scores, feature hashes, original/final typed decisions and variable five/six-class distributions. Require a fresh report identity, `utility_parity_passed` and exact probe count. Altered metadata/hashes, forged statuses and missing rows are rejected. These parity checks do not certify quality or promote the model. Ordinary APK/flavor dependencies, permissions, Room, tools and providers remain unchanged.

Locally passed: four unique candidate fits plus reproducible refits, original-v1 reference agreement, 4,226 exported/committed observations, 66 actual runtime JUnit tests (seven added), real optimizer/policy controls, eight new data guards plus prior guards, and host instrumentation fixtures. Strict release8/Gradle and actual ART API30 results require this PR's CI. No physical Infinix test was performed. The next step needs fresh supported developer/device and mixed-action validation after a prespecified change; do not tune these observed tests into an artificial success claim.
