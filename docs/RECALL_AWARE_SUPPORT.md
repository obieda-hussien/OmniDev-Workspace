# Recall-aware support experiment (v9)

V8's fixed-head cutoff rejected 48 previously correct answers while reducing old errors. V9 tests positive-weighted fitting and a broader engineering preservation guard. The support proposer, lexical pair features, reference corpus and production default remain frozen. The experiment is explicit and disabled in the application.

The reservation was committed before fitting or evaluating the 90 new questions: `e464d68d95e2e32525af613869f8aebacfce712f`.

## Training and policy

Use the existing 507 TRAIN pairs (411 original group-excluded ranking targets plus 96 contrast rows), DEV135, TEMP66 and POLICY75. No new evaluation questions update weights. Eight sparse binary AdamW fits vary positive loss weight **1 or 4**, L2 **0.0001 or 0.001** and seed **42 or 97**. The optimizer budget remains batch32, rate0.01, clip5, decay0.0001, at most100 epochs and patience12.

Every configuration and snapshot is selected against the same **positive-cost4 macro-language DEV BCE**, normalized by total example cost within each language. This is a cost-weighted objective, not an unweighted probabilistic NLL or a number directly comparable to v8's DEV NLL. The unit-weight and weighted arms both remain exported. TEMP66 independently selects an unweighted-NLL temperature from the reserved grid.

The selected positive-weight4 head uses seed97, L2 0.001, snapshot97 out of100 epochs, DEV cost4 BCE **0.302474** versus **0.429575** for the best unit-weight arm. Its temperature is **1.5**. The unit arm uses seed42, L2 0.0001, snapshot31/43 and temperature0.75. The selected fit reaches the training budget; evaluation does not justify extending it after observing the new challenge.

The new guard contains TRAIN507 + DEV135 + TEMP66 + POLICY75 = **783 rows**. For each grid cutoff0..1 by0.025, require preserving every full-proposer accepted-correct guard row, then minimize accepted-wrong on POLICY75 and prefer the largest remaining cutoff. This chooses **0.5**, preserving all **512** accepted-correct guard rows. A separately exported POLICY75-only comparison chooses **0.675** for the same head. No old test or new90 labels select either cutoff.

The guard deliberately includes fitting and model-selection data, and TRAIN proposals can see their own TRAIN exemplar. It is an engineering preservation constraint, **not independent validation, out-of-fold performance, conformal prediction or a population risk bound**. TRAIN loss targets still use group-excluded ranking; the full-proposer guard measures a different operational path. Temperature and policy roles remain excluded from fitting. The selected head has1,025 FP32 parameters (4,100 raw bytes), excluding the proposer, Java objects and indexes. No ensemble or extra runtime parameter is added.

## Results and limits

The 90 new questions are 15 assistant-authored supported/unsupported scenario pairs in English, Arabic and mixed text. They cover known FAQ topics; they are not independently reviewed user traffic, unseen documents or semantic OOD. All prior1,782 annotations and labels remain unchanged and are observed regressions.

| Evaluation | Frozen proposer correct / wrong | Guarded v9 correct / wrong | Previously correct lost |
|---|---:|---:|---:|
| Observed1,782 | 841 / 25 | 841 / 24 | 0 |
| Original1,488, subset above | 748 / 12 | 748 / 12 | 0 |
| New authored90 | 29 / 6 | 29 / 5 | 0 |
| v7 typo/word-form120, subset of observed | 42 / 0 | 42 / 0 | 0 |
| Fitting/development/calibration guard783, overlapping observed | 512 / 13 | 512 / 12 | 0 |

The POLICY75-only cutoff keeps808 correct/12 wrong on observed1,782 but loses33 correct. This reproduces the small-calibration preservation failure with the new head. The larger guard avoids those observed losses. The new90 gain is **one fewer wrong acceptance**, not broad evidence of stronger reasoning or general accuracy. Four unsupported questions and one wrong-topic positive remain accepted. English versus mixed phrasing still matters. Full loss/error IDs and language reports are committed.

There is a material classifier tradeoff: on explicit-reference classification at probability0.5, the unit-weight arm achieves **72/90**, unweighted NLL **0.410068**, while the selected weighted arm achieves **50/90**, NLL **0.625454**. Positive weighting improves the chosen recall-cost objective but worsens this balanced classification metric. The deployed guarded unit arm accepts29 correct/6 wrong; the selected weighted arm accepts29/5. The arm was chosen by DEV, not these new results. Neither arm generates text, repairs a wrong topic or upgrades a refused proposal.

The frozen v1 default remains exact and `production_eligible` staysfalse. Small targeted gains plus observed preservation are insufficient for promotion. V8's negative results and all old checkpoints/provenance remain unchanged. Next quality work needs separate review/evaluation of the remaining errors and the loss of negative-class specificity, rather than a cutoff chosen from these observed90 labels.

## Reproduction and runtime checks

```bash
bash scripts/train_recall_support.sh
bash scripts/prepare_neural_android_fixtures.sh /tmp/omni-recall-fixtures
```

JDK17 CI uses `--release 8`. The explicit local `--source-target-only` fallback checks language/bytecode targeting but does not establish Java8 API compatibility. All training sources still compile without checkpoint classes, using reflective experiment loading.

The training smoke verifies **512** finite-difference coordinates from actual positive/negative rows under both loss weights, independent DEV cost normalization, deterministic snapshots and group/role/observed-guard/duplicate rejection. Compiled reload checks cover1,872 selected predictions,3,744 matched-arm predictions,1,872 policy-only predictions, original source/rank identity and1,872 retained-default decisions. The same packaged selected-model contract runs on host and API30 ART; the latter emits a current-run `recall-correctness.json`. Seven corrupt-fixture cases reject forged flags, missing/duplicate rows, changed references, NaN scores and changed exemplars.

`host-timings.json` contains descriptive interleaved host timings with no timing pass threshold. No physical Infinix benchmark, peak-heap measurement or independent human labeling is claimed. CI state must be read for the exact commit; a configured ART test is not a completed ART test.
