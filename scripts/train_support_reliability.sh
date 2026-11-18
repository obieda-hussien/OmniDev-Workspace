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
python3 scripts/test_support_reliability.py
mkdir -p build/support-reliability-v6
compile_java "${compat[@]}" -encoding UTF-8 -d "$classes" \
  neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java \
  neural-core/training/com/omnidev/workspace/domain/neural/*.java \
  neural-core/checkpoints/product-support-v1/TrainedProductSupport.java \
  neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java \
  neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java \
  neural-core/smoke/SupportReliabilityTrainingSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.SupportReliabilityTrainingSmoke
java -cp "$classes" com.omnidev.workspace.domain.neural.SupportReliabilityExperiment build/support-reliability-v6 > build/support-reliability-v6/fit.log
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" \
  build/support-reliability-v6/TrainedSupportReliability.java neural-core/smoke/SupportReliabilityCheckpointSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.SupportReliabilityCheckpointSmoke build/support-reliability-v6
if [[ -f neural-core/checkpoints/support-reliability-v6/TrainedSupportReliability.java ]]; then
  for file in TrainedSupportReliability.java expected.tsv risk-coverage.tsv; do
    cmp "build/support-reliability-v6/$file" "neural-core/checkpoints/support-reliability-v6/$file"
  done
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" neural-core/checkpoints/support-reliability-v6/TrainedSupportReliability.java
  java -cp "$classes" com.omnidev.workspace.domain.neural.SupportReliabilityCheckpointSmoke build/support-reliability-v6
  python3 scripts/finalize_support_reliability.py --verify
else
  python3 scripts/finalize_support_reliability.py
fi
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" neural-core/smoke/SupportReliabilityBenchmark.java
java -cp "$classes" com.omnidev.workspace.domain.neural.SupportReliabilityBenchmark build/support-reliability-v6
