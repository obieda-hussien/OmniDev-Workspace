#!/usr/bin/env bash
set -euo pipefail
compat=(--release 8)
if [[ "${1:-}" == "--source-target-only" ]]; then compat=(-source 8 -target 8 -Xlint:-options); elif [[ $# != 0 ]]; then exit 2; fi
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
compile_java(){ if command -v javac >/dev/null 2>&1; then javac "$@"; else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi; }
python3 scripts/prepare_product_support.py build/product-support
python3 scripts/test_product_support.py
compile_java "${compat[@]}" -encoding UTF-8 -d "$out" neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java neural-core/training/com/omnidev/workspace/domain/neural/ProductSupportExperiment.java
java -cp "$out" com.omnidev.workspace.domain.neural.ProductSupportExperiment build/product-support > build/product-support/fit.json
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" build/product-support/TrainedProductSupport.java neural-core/smoke/ProductSupportCheckpointSmoke.java neural-core/smoke/ProductSupportBenchmark.java
java -cp "$out" com.omnidev.workspace.domain.neural.ProductSupportCheckpointSmoke build/product-support
if [[ -f neural-core/checkpoints/product-support-v1/TrainedProductSupport.java ]]; then
  cmp build/product-support/TrainedProductSupport.java neural-core/checkpoints/product-support-v1/TrainedProductSupport.java
  cmp build/product-support/expected.tsv neural-core/checkpoints/product-support-v1/expected.tsv
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/product-support-v1/TrainedProductSupport.java
  java -cp "$out" com.omnidev.workspace.domain.neural.ProductSupportCheckpointSmoke build/product-support
fi

java -cp "$out" com.omnidev.workspace.domain.neural.ProductSupportBenchmark build/product-support
python3 scripts/finalize_product_support.py --verify
