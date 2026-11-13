#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 || $# -gt 2 ]]; then echo 'Usage: prepare_neural_android_fixtures.sh OUTPUT [--source-target-only]' >&2; exit 2; fi
fixture_output=$1
fixture_classes=$(mktemp -d)
trap 'rm -rf "$fixture_classes"' EXIT
compat=(--release 8)
if [[ "${2:-}" == --source-target-only ]]; then compat=(-source 8 -target 8 -Xlint:-options)
elif [[ -n "${2:-}" ]]; then echo 'Unknown compatibility option' >&2; exit 2; fi
compile_java() {
  if command -v javac >/dev/null 2>&1; then javac "$@"
  else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi
}
python3 scripts/import_clinc_rejection.py --verify neural-core/fixtures/clinc-subset
compile_java "${compat[@]}" -encoding UTF-8 -d "$fixture_classes" \
  neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java \
  neural-core/training/com/omnidev/workspace/domain/neural/*.java
java -cp "$fixture_classes" com.omnidev.workspace.domain.neural.TextIntentExperiment \
  neural-core/fixtures/text-intents.tsv "$fixture_classes/TrainedTextIntentCheckpoint.java"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$fixture_classes" -d "$fixture_classes" \
  "$fixture_classes/TrainedTextIntentCheckpoint.java"
java -cp "$fixture_classes" com.omnidev.workspace.domain.neural.GatedRejectionExperiment \
  neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv \
  "$fixture_classes/TrainedRefusalGateCheckpoint.java" neural-core/fixtures/decision-challenges.tsv
compile_java "${compat[@]}" -encoding UTF-8 -cp "$fixture_classes" -d "$fixture_classes" \
  "$fixture_classes/TrainedRefusalGateCheckpoint.java" neural-core/smoke/AndroidFixtureExporter.java
java -cp "$fixture_classes" AndroidFixtureExporter "$fixture_output"
fixture_package="$fixture_output/java/com/omnidev/workspace/neural/probe"
mkdir -p "$fixture_package"
for checkpoint in TrainedTextIntentCheckpoint TrainedRefusalGateCheckpoint; do
  { echo 'package com.omnidev.workspace.neural.probe;'; cat "$fixture_classes/$checkpoint.java"; } > "$fixture_package/$checkpoint.java"
done
python3 - "$fixture_output" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
paths = sorted((root / 'java').rglob('*.java')) + [root / 'assets/probes.tsv']
with (root / 'assets/provenance.txt').open('a') as output:
    for path in paths:
        output.write(f'{path.relative_to(root)} sha256={hashlib.sha256(path.read_bytes()).hexdigest()}\n')
PY
# Compile and exercise the exact packaged checkpoints/contract used by ART.
compile_java "${compat[@]}" -encoding UTF-8 -cp "$fixture_classes" -d "$fixture_classes" \
  "$fixture_package/"*.java neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/NeuralProbeContract.java \
  neural-core/smoke/AndroidProbeSmoke.java
java -cp "$fixture_classes" AndroidProbeSmoke "$fixture_output/assets/probes.tsv"
