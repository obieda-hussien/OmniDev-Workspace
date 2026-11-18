#!/usr/bin/env bash
set -euo pipefail
compat=(--release 8)
if [[ "${1:-}" == --source-target-only ]]; then compat=(-source 8 -target 8 -Xlint:-options); elif [[ $# != 0 ]]; then exit 2; fi
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
compile_java(){ if command -v javac >/dev/null 2>&1; then javac "$@"; else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi; }
python3 scripts/prepare_product_support.py build/product-support
python3 scripts/test_query_memory.py
python3 scripts/prepare_query_memory.py build/query-memory-v5
compile_java "${compat[@]}" -encoding UTF-8 -d "$classes" neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java neural-core/training/com/omnidev/workspace/domain/neural/*.java neural-core/checkpoints/product-support-v1/TrainedProductSupport.java neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java neural-core/smoke/QueryMemoryTrainingSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.QueryMemoryTrainingSmoke
java -cp "$classes" com.omnidev.workspace.domain.neural.QueryMemoryExperiment build/query-memory-v5 > build/query-memory-v5/fit.log
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" build/query-memory-v5/TrainedQueryMemory.java neural-core/smoke/QueryMemoryCheckpointSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.QueryMemoryCheckpointSmoke build/query-memory-v5
if [[ -f neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java ]]; then
 cmp build/query-memory-v5/TrainedQueryMemory.java neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java
 cmp build/query-memory-v5/expected.tsv neural-core/checkpoints/query-memory-v5/expected.tsv
 cmp build/query-memory-v5/exemplars.tsv neural-core/checkpoints/query-memory-v5/exemplars.tsv
 compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java
 java -cp "$classes" com.omnidev.workspace.domain.neural.QueryMemoryCheckpointSmoke build/query-memory-v5
 python3 scripts/finalize_query_memory.py --verify
else
 python3 scripts/finalize_query_memory.py
fi
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" neural-core/smoke/QueryMemoryBenchmark.java
java -cp "$classes" com.omnidev.workspace.domain.neural.QueryMemoryBenchmark build/query-memory-v5
