#!/usr/bin/env bash
set -euo pipefail
compat=(--release 8)
if [[ "${1:-}" == --source-target-only ]]; then compat=(-source 8 -target 8 -Xlint:-options); elif [[ $# != 0 ]]; then exit 2; fi
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
compile_java(){ if command -v javac >/dev/null 2>&1; then javac "$@"; else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi; }
python3 scripts/prepare_product_support.py build/product-support
python3 scripts/test_support_data_v4.py
mkdir -p build/support-data-v4
compile_java "${compat[@]}" -encoding UTF-8 -d "$classes" neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java neural-core/training/com/omnidev/workspace/domain/neural/*.java neural-core/checkpoints/product-support-v1/TrainedProductSupport.java neural-core/checkpoints/hybrid-support-v3/TrainedHybridSupport.java neural-core/smoke/ExpandedSupportTrainingSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.ExpandedSupportTrainingSmoke
java -cp "$classes" com.omnidev.workspace.domain.neural.ExpandedSupportExperiment build/support-data-v4 > build/support-data-v4/fit.log
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" build/support-data-v4/TrainedExpandedSupport.java neural-core/smoke/ExpandedSupportCheckpointSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.ExpandedSupportCheckpointSmoke build/support-data-v4
if [[ -f neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java ]]; then
 cmp build/support-data-v4/TrainedExpandedSupport.java neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java
 cmp build/support-data-v4/expected.tsv neural-core/checkpoints/support-data-v4/expected.tsv
 compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java
 java -cp "$classes" com.omnidev.workspace.domain.neural.ExpandedSupportCheckpointSmoke build/support-data-v4
 python3 scripts/finalize_expanded_support.py --verify
else
 python3 scripts/finalize_expanded_support.py
fi
