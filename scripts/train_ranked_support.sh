#!/usr/bin/env bash
set -euo pipefail
compat=(--release 8)
if [[ "${1:-}" == "--source-target-only" ]]; then compat=(-source 8 -target 8 -Xlint:-options); elif [[ $# != 0 ]]; then exit 2; fi
mkdir -p build/product-support-v2
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
compile_java(){ if command -v javac >/dev/null 2>&1; then javac "$@"; else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi; }
python3 scripts/prepare_product_support.py build/product-support
python3 scripts/test_ranked_support.py
compile_java "${compat[@]}" -encoding UTF-8 -d "$out" neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java neural-core/training/com/omnidev/workspace/domain/neural/ProductSupportExperiment.java neural-core/training/com/omnidev/workspace/domain/neural/RankedSupportTrainer.java neural-core/training/com/omnidev/workspace/domain/neural/RankedSupportExperiment.java neural-core/checkpoints/product-support-v1/TrainedProductSupport.java neural-core/smoke/RankedSupportTrainingSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.RankedSupportTrainingSmoke
java -cp "$out" com.omnidev.workspace.domain.neural.RankedSupportExperiment build/product-support-v2 > build/product-support-v2/fit.json
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" build/product-support-v2/TrainedRankedSupport.java neural-core/smoke/RankedSupportCheckpointSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.RankedSupportCheckpointSmoke build/product-support-v2
if [[ -f neural-core/checkpoints/product-support-v2/TrainedRankedSupport.java ]]; then
  cmp build/product-support-v2/TrainedRankedSupport.java neural-core/checkpoints/product-support-v2/TrainedRankedSupport.java
  cmp build/product-support-v2/expected.tsv neural-core/checkpoints/product-support-v2/expected.tsv
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/product-support-v2/TrainedRankedSupport.java
  java -cp "$out" com.omnidev.workspace.domain.neural.RankedSupportCheckpointSmoke build/product-support-v2
  python3 scripts/finalize_ranked_support.py --verify
else
  python3 scripts/finalize_ranked_support.py
fi
