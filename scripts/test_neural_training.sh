#!/usr/bin/env bash
set -euo pipefail
training_out=$(mktemp -d)
trap 'rm -rf "$training_out"' EXIT
training_compat=(--release 8)
if [[ "${1:-}" == "--source-target-only" ]]; then training_compat=(-source 8 -target 8 -Xlint:-options); fi
compile_java() {
  if command -v javac >/dev/null 2>&1; then javac "$@"
  else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi
}
compile_java "${training_compat[@]}" -encoding UTF-8 -d "$training_out" \
  neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java \
  neural-core/training/com/omnidev/workspace/domain/neural/*.java \
  neural-core/smoke/DecisionTrainingSmoke.java
java -cp "$training_out" DecisionTrainingSmoke "$training_out/TrainedDecisionCheckpoint.java"
compile_java "${training_compat[@]}" -encoding UTF-8 -cp "$training_out" -d "$training_out" "$training_out/TrainedDecisionCheckpoint.java"
java -cp "$training_out" DecisionTrainingSmoke --verify
