# Frozen-model decision risk audit

Starting main: `117f0c720d7685f3f1d1edbfe12c0556705c7a80`. This is evaluation infrastructure,
not an improved checkpoint or production activation. The original encoder, corpus, parameters,
temperature and 0.60/0.15 thresholds are unchanged. The runner loads the compiled checkpoint from
the existing text training/export check; challenge rows never enter fitting or threshold selection.

The new 25-row Arabic/English/mixed challenge fixture is hand-authored, not an independently annotated
production benchmark. Ten rows combine domains or omit a specific objective, ten request topics outside
the five narrow training domains, and five are known-domain positive controls. The declared single-choice
evaluation contract requires abstention on the first twenty. This does not imply that Omni itself cannot
handle those tasks: a broader agent could clarify, decompose or route them. A domain prediction is never
execution authorization. Negation/action-consent detection is not claimed by this domain classifier.

`DecisionRiskMetrics` accumulates at most one million observations without retaining texts or predictions.
It reports coverage, accepted errors, selective error and required/refused abstention counts. Known-class
top-label ECE uses ten equal-width probability bins and includes known rows even when refused. Ambiguous
and out-of-scope rows have no correct class probability, so they do not enter ECE or known-class accuracy.
An accepted choice on a required-abstention row counts as an error. Zero coverage has undefined selective
error (`null`), not a perfect accuracy score. These are descriptive fixture metrics, not safety guarantees.

The runner rejects duplicate IDs, normalized text repeated within the challenge or original corpus,
unknown labels/languages/kinds, mismatched expectations and missing groups before predicting or printing
results. Reports include challenge SHA-256 and always set `production_eligible:false`. Existing scripts
exercise the actual runner's rejection paths and retain all earlier training/export regressions.

Observed locally with the frozen checkpoint: all five positive controls were correct, but only 2/20
required abstentions occurred. Nine ambiguous and nine out-of-scope rows were accepted. Across all 25 rows,
23 choices were accepted and 18 violated the evaluation contract (selective error 18/23). Known-row ECE
was about 0.016 on just five easy controls; that low number did not establish OOD safety. Neither this
small fixture nor the earlier 27/30 result supports production deployment or a comparison against Jev.

Run `bash scripts/test_neural_training.sh` (or the documented local source/target-only fallback).
Do not tune against these now-observed challenges and call them unseen evaluation. Future work requires
independently reviewed training/development/calibration material, a new sealed test set, measured OOD
abstention and hardware evaluation. This change does not touch APK dependencies, flavor permissions,
P0/P2/P5/P6, provider routing, model weights or any tool execution path.
