# Offline decision training (experimental)

This is a narrow numerical decision specialist, not a reproduction of Jev's model or RLCD training.
It does not understand arbitrary natural-language questions. The caller supplies an ordered feature
schema and a fixed label contract. Text feature extraction and a representative labelled corpus are
still required before claiming Arabic/English tool-routing quality.

`TinyDecisionModel` wraps immutable TinyNeuralRuntime logits with stable softmax, temperature,
caller-defined choice labels and explicit abstention on low probability, small margins or ties.
Probability is a model estimate, not verified correctness, permission or an out-of-distribution detector.
It never executes a tool or creates evidence. The module remains outside the APK.

`OfflineDecisionTrainer` is desktop-only code under `neural-core/training`, excluded from the library
and Android dependency. It supports a linear softmax baseline and a one-hidden-layer tanh network,
supervised cross-entropy, seeded SGD and L2 regularization. Training inputs must be finite normalized
vectors, with explicit limits on examples, dimensions, classes, hidden units and epochs. No external
ML dependency, network calls, credentials or private user conversations are involved.

The fitted rich-text experiment permits up to 1,024 input dimensions with a separate
20,000-trained-parameter cap. All original <=256-dimensional shapes remain supported;
the inference runtime's 100,000-parameter cap is unchanged. Wider linear binary heads
can export an immutable calibrated log-odds layer for fused inference.

Use scenario/group identifiers to keep paraphrases together. Call `requireDisjointGroups` on train,
development, calibration and holdout before fitting. Select architecture on development only, fit
temperature on separate calibration data, and inspect holdout only after both choices are fixed.
Calibration rejects groups seen during training. These mechanisms cannot detect incorrectly assigned
group identifiers or a developer manually tuning against holdout results.

`Model.runtime()` exports immutable learned parameters. `javaSource(temperature)` emits a Java 8
checkpoint class with its temperature; the training check compiles and reloads that class and checks
prediction parity. Binary log-odds exports support the existing six-feature NeuralToolRanker and
five-feature NeuralEvidenceReranker shapes. Real data must use those exact feature meanings and order;
shape compatibility alone is not evidence of ranking quality.
Exported arrays use helper methods with at most 256 assignments, keeping JVM methods bounded.
The export/reload check also covers the original maximum-width trainer fixture (256 inputs, 64 hidden units,
32 classes; 18,528 parameters), independently of the small learning fixture.

Run `bash scripts/test_neural_training.sh` with a full JDK. It trains both architectures using a fixed
synthetic numerical fixture and prints split sizes, development log loss, selected architecture,
heldout log loss and FP32 weight bytes. Outputs are temporary and are removed afterward; no fixture
checkpoint is bundled for production. `--source-target-only` is an explicit local fallback for images
missing Java release signatures; CI always uses `--release 8` and Gradle JUnit tests.

The fixture proves training, split guards, portable export and scorer interoperability. It is not an
Omni accuracy benchmark, a device latency/memory measurement, or a comparison against Jev. Weight bytes
exclude the JVM, arrays, activations, features and application memory. No model is enabled automatically.
Before application integration: curate independently labelled real tasks, evaluate ranking/abstention
and calibration against deterministic baselines, test API 30 on hardware, then use shadow-only rollout.

The separate [bilingual text experiment](TEXT_INTENT_EXPERIMENT.md) now exercises a bounded text encoder,
an authored multilingual corpus, a centroid comparison and text-checkpoint parity. Its results do not
authorize production integration.
