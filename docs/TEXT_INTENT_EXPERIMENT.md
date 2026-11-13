# Bilingual text intent experiment

Started from main `2415a4a36d1b756235b39d9a1811157da93667a0`. This adds an actual text-to-features-to-trained-choice
path to the isolated neural module. It remains outside the APK and does not select or execute tools, grant
permissions, reconcile effects, or verify completion. It is not a Jev replica or a general language model.

`BilingualTextEncoder` uses 256 signed FNV-hashed word/character n-gram features, log counts and L2 normalization.
Normalization is locale-independent and handles Arabic diacritics, tatweel and common alef/ya variants. Inputs
over 2,048 UTF-16 characters, including NFKC expansion, are rejected instead of silently truncated. This is
lexical representation, with hash collisions and no pretrained semantic knowledge. `TextIntentModel` binds
the feature version and input shape, and forces abstention on text without lexical features.

The versioned TSV contains 120 hand-authored fixtures, not collected user tasks or independently annotated
production data. Five broad domains are repository, build, device, knowledge and chat. Each scenario has Arabic,
English and mixed-language paraphrases in the same split. Split sizes are 60 train / 15 development / 15 calibration /
30 holdout. A group cannot cross splits or labels; duplicate normalized texts and missing split classes are rejected.
The corpus hash is included in each evaluation. Group IDs cannot prove that an author avoided all semantic overlap.

The fixed experiment compares linear softmax and a 16-hidden-unit tanh network on development NLL, chooses
temperature on calibration, then measures holdout once the choices are fixed. The comparison baseline is a
nearest class centroid learned only from training with the same lexical features. It is not the production P4
tool selector. Probability/margin thresholds are fixed at 0.60/0.15 before evaluation. Reports include counts,
accuracy, macro F1, NLL, multiclass Brier score and accepted/correct counts, separately for each language.

The initial local run selected MLP16 (4,197 parameters, temperature 0.75). Both it and the centroid baseline
classified 27/30 holdout examples correctly. The network accepted 28 predictions, including three errors.
Thus there is **no demonstrated accuracy gain over this baseline**, and confidence did not eliminate mistakes.
These small authored-fixture results do not establish real-world accuracy, robustness to ambiguous or unrelated
requests, performance on Egyptian dialect generally, or latency/memory on a phone. No promotion is authorized by
the report. Evaluation prints `production_eligible:false` unconditionally.

Run `bash scripts/test_neural_training.sh` on a full JDK (the documented source/target-only fallback is available
locally). This trains and exports both numerical and text experiments, compiles the generated checkpoints,
verifies exact prediction/schema/calibration reload parity, and exercises actual corpus rejection paths.
The temporary text checkpoint includes its encoder version, label order and temperature. No fixture weights
are bundled in production. Repeating the fixed run for parity is not model tuning.

Freeze this corpus and model configuration as a development regression fixture now that its results are known.
Future quality claims require a separate independently reviewed corpus and a fresh evaluation set kept out of
feature/hyperparameter decisions, including ambiguity, out-of-domain, negation and mixed-task challenges.
Only demonstrated improvements over deterministic baselines and real API 30 hardware measurements can justify
a shadow adapter in the app. P0/P2/P5/P6 and flavor authorization remain final authorities.

The [frozen-model risk audit](DECISION_RISK_EVALUATION.md) adds separate ambiguity/out-of-scope
challenges and selective-error/calibration metrics. Its observed failures reinforce the deployment block.
