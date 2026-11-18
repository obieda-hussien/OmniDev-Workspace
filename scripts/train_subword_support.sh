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
python3 scripts/test_subword_support.py
mkdir -p build/subword-support-v7
compile_java "${compat[@]}" -encoding UTF-8 -d "$classes" \
  neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java \
  neural-core/training/com/omnidev/workspace/domain/neural/*.java \
  neural-core/checkpoints/product-support-v1/TrainedProductSupport.java \
  neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java \
  neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java \
  neural-core/checkpoints/support-reliability-v6/TrainedSupportReliability.java \
  neural-core/smoke/SupportReliabilityTrainingSmoke.java \
  neural-core/smoke/SubwordSupportTrainingSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.SubwordSupportTrainingSmoke
java -cp "$classes" com.omnidev.workspace.domain.neural.SubwordSupportExperiment build/subword-support-v7 > build/subword-support-v7/fit.log
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" \
  build/subword-support-v7/TrainedSubwordSupport.java neural-core/smoke/SubwordSupportCheckpointSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.SubwordSupportCheckpointSmoke build/subword-support-v7
if [[ -f neural-core/checkpoints/subword-support-v7/TrainedSubwordSupport.java ]]; then
  for file in TrainedSubwordSupport.java expected.tsv arm-expected.tsv; do
    cmp "build/subword-support-v7/$file" "neural-core/checkpoints/subword-support-v7/$file"
  done
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" neural-core/checkpoints/subword-support-v7/TrainedSubwordSupport.java
  java -cp "$classes" com.omnidev.workspace.domain.neural.SubwordSupportCheckpointSmoke build/subword-support-v7
  python3 scripts/finalize_subword_support.py --verify
else
  python3 scripts/finalize_subword_support.py
fi
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" neural-core/smoke/SubwordSupportBenchmark.java
java -cp "$classes" com.omnidev.workspace.domain.neural.SubwordSupportBenchmark build/subword-support-v7
