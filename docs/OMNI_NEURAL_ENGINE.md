# Omni Neural Core — isolated Java experiment

This module is **inside OmniDev-Workspace** to make cross-repository state and
CI unnecessary. It is NOT wired into the active AgentPipeline, P0, P1, P2, P3,
Smart Tool Surface, mode decisions or permissions. It is not shipped in the
APK because the app does not declare a dependency on this module.

## What exists
* TinyNeuralRuntime: immutable, bounded dense FP32 inference (up to 100,000
  parameters / 8 layers), Java 8 bytecode, no Python / JNI / external ML SDK.
* OmniRamLite: fully local bounded BM25 + character n-gram chunk retrieval;
  source document IDs, exact UTF-16 offsets, exact quotations and abstention on
  no lexical overlap; Arabic diacritic/alef normalization.
* WeightEditGate: offline no-gradient candidate/holdout MSE gate. It evaluates
  **pre-existing** original and proposed models; it does not generate/edit
  weights itself, train a model or install new weights automatically.
* NeuralEvidenceReranker: optional output-preserving rescore of existing hits
  with a **user-supplied** 5-input/1-output neural network. The influence is
  clamped to 0..0.5. No bundled neural checkpoint or quality claim.
* EvidenceMetrics: labelled recall@k, reciprocal rank and nDCG@k plus exact
  quote verification.

## Important limits
* NOT a port of Racore's Mega-RAM-100K weights/architecture. It neither
  processes 10M tokens nor claims the published 3–20ms latency.
* Retrieval is in-memory, bounded to 128 documents / 1,000,000 Java chars,
  900-character overlapping chunks. No persistence or incremental indexing.
* This is an evaluation harness, not an LLM, an intent classifier, an
  autonomous tool selector, or production RAG.
* No network, filesystem, Shizuku, root, runtime permissions or telemetry.
* Source text is UNTRUSTED data, not execution instructions.
* Neural ranking must never grant permissions, approve actions, attribute
  evidence to a fake P3 step, or override the P0 completion gate.

## Verification
Locally with Gradle and JDK 17:
    ./gradlew :neural-core:test
Standalone smoke with Java 8-compatible compilation (no Gradle):
    mkdir -p out/neural
    javac --release 8 -encoding UTF-8 -d out/neural \
      neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java \
      neural-core/smoke/NeuralPrototypeSmoke.java
    java -cp out/neural NeuralPrototypeSmoke

CI: .github/workflows/neural-core-ci.yml, separate from Android CI.
This workflow compiles without invoking Android build tools and also runs
Gradle JUnit tests. No APK, device benchmark or Python numerical parity
has been asserted yet.

## Next independent milestones
1. Verify original Mega-RAM license, source revision and exact numerical
   fixtures; build a separate parity port if warranted, no false claims.
2. Curate labelled Arabic/English/Kotlin search corpora and compare with
   LocalCodeRetriever, SQLite FTS and BM25; measure recall@5, nDCG@10, MRR,
   invalid citations and abstentions. Keep held-out examples untouched.
3. Build an on-disk incremental index and safe document invalidation; run
   10k/100k/1M-token memory and performance tests on Android API 30
   hardware with p50/p95 and peak heap.
4. Evaluate an independently verified reranker checkpoint and an optional
   tool-intent specialist in shadow mode (never granting access).
5. Only then integrate a behind-flag adapter with Existing P0/P2/P3 and
   Smart Tool Surface, and check all five product flavors.
