#!/usr/bin/env bash
set -euo pipefail
compat=(--release 8)
if [[ "${1:-}" == --source-target-only ]]; then compat=(-source 8 -target 8 -Xlint:-options)
elif [[ $# != 0 ]]; then exit 2; fi
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
compile_java(){ if command -v javac >/dev/null 2>&1; then javac "$@"; else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi; }
python3 scripts/prepare_product_support.py build/product-support
python3 scripts/prepare_query_memory.py build/query-memory-v5
python3 scripts/test_contrastive_support.py
mkdir -p build/contrastive-support-v10
compile_java "${compat[@]}" -encoding UTF-8 -d "$classes" \
  neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java \
  neural-core/training/com/omnidev/workspace/domain/neural/*.java \
  neural-core/checkpoints/product-support-v1/TrainedProductSupport.java \
  neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java \
  neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java \
  neural-core/checkpoints/subword-support-v7/TrainedSubwordSupport.java \
  neural-core/checkpoints/answerability-support-v8/TrainedAnswerability*.java \
  neural-core/checkpoints/recall-support-v9/TrainedRecallAwareSupport.java \
  neural-core/smoke/SupportReliabilityTrainingSmoke.java neural-core/smoke/ContrastiveSupportTrainingSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.ContrastiveSupportTrainingSmoke
java -cp "$classes" com.omnidev.workspace.domain.neural.ContrastiveSupportExperiment build/contrastive-support-v10 > build/contrastive-support-v10/fit.log
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" \
  build/contrastive-support-v10/TrainedContrastiveSupport.java neural-core/smoke/AnswerabilityCheckpointSmoke.java neural-core/smoke/ContrastiveSupportCheckpointSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.ContrastiveSupportCheckpointSmoke build/contrastive-support-v10
if [[ -f neural-core/checkpoints/contrastive-support-v10/TrainedContrastiveSupport.java ]]; then
  for file in TrainedContrastiveSupport.java expected.tsv arm-expected.tsv policy-expected.tsv; do
    cmp "build/contrastive-support-v10/$file" "neural-core/checkpoints/contrastive-support-v10/$file"
  done
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" neural-core/checkpoints/contrastive-support-v10/TrainedContrastiveSupport.java
  java -cp "$classes" com.omnidev.workspace.domain.neural.ContrastiveSupportCheckpointSmoke build/contrastive-support-v10
  python3 scripts/finalize_contrastive_support.py --verify
else
  python3 scripts/finalize_contrastive_support.py
fi
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" neural-core/smoke/ContrastiveSupportBenchmark.java
java -cp "$classes" com.omnidev.workspace.domain.neural.ContrastiveSupportBenchmark build/contrastive-support-v10
