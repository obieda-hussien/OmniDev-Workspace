#!/usr/bin/env bash
set -euo pipefail
compat=(--release 8)
if [[ "${1:-}" == --source-target-only ]]; then compat=(-source 8 -target 8 -Xlint:-options); elif [[ $# != 0 ]]; then exit 2; fi
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
mkdir -p build/hybrid-support-v3
compile_java(){ if command -v javac >/dev/null 2>&1; then javac "$@"; else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi; }
python3 scripts/prepare_product_support.py build/product-support
python3 scripts/test_hybrid_support.py
compile_java "${compat[@]}" -encoding UTF-8 -d "$out" neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java neural-core/training/com/omnidev/workspace/domain/neural/ProductSupportExperiment.java neural-core/training/com/omnidev/workspace/domain/neural/RankedSupportExperiment.java neural-core/training/com/omnidev/workspace/domain/neural/RankedSupportTrainer.java neural-core/training/com/omnidev/workspace/domain/neural/HybridSupportExperiment.java neural-core/checkpoints/product-support-v1/TrainedProductSupport.java neural-core/checkpoints/product-support-v2/TrainedRankedSupport.java neural-core/smoke/HybridSupportTrainingSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.HybridSupportTrainingSmoke
java -cp "$out" com.omnidev.workspace.domain.neural.HybridSupportExperiment build/hybrid-support-v3 > build/hybrid-support-v3/fit.json
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" build/hybrid-support-v3/TrainedHybridSupport.java neural-core/smoke/HybridSupportCheckpointSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.HybridSupportCheckpointSmoke build/hybrid-support-v3
if [[ -f neural-core/checkpoints/hybrid-support-v3/TrainedHybridSupport.java ]]; then
 cmp build/hybrid-support-v3/TrainedHybridSupport.java neural-core/checkpoints/hybrid-support-v3/TrainedHybridSupport.java
 cmp build/hybrid-support-v3/expected.tsv neural-core/checkpoints/hybrid-support-v3/expected.tsv
 compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/hybrid-support-v3/TrainedHybridSupport.java
 java -cp "$out" com.omnidev.workspace.domain.neural.HybridSupportCheckpointSmoke build/hybrid-support-v3
 python3 scripts/finalize_hybrid_support.py --verify
else
 python3 scripts/finalize_hybrid_support.py
fi
