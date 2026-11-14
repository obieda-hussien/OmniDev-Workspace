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
python3 scripts/prepare_massive_refusal.py --verify
python3 scripts/reserve_residual_eval.py --verify
python3 scripts/reserve_utility_eval.py --verify
python3 scripts/prepare_prototype_challenges.py --verify
python3 scripts/reserve_trust_eval.py --verify
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
  "$fixture_classes/TrainedRefusalGateCheckpoint.java" \
  neural-core/checkpoints/bilingual-refusal-v1/TrainedBilingualRefusalCheckpoint.java \
  neural-core/checkpoints/rich-text-v1/*.java neural-core/checkpoints/public-refusal-v1/*.java \
  neural-core/checkpoints/public-decision-v1/*.java neural-core/checkpoints/residual-adapter-v1/*.java \
  neural-core/checkpoints/utility-expert-v2/*.java neural-core/checkpoints/prototype-support-v1/*.java neural-core/checkpoints/expert-trust-v1/*.java \
  neural-core/smoke/AndroidFixtureExporter.java neural-core/smoke/ResidualAndroidFixtureExporter.java neural-core/smoke/UtilityAndroidFixtureExporter.java neural-core/smoke/PrototypeAndroidFixtureExporter.java neural-core/smoke/ExpertTrustAndroidFixtureExporter.java
java -cp "$fixture_classes" AndroidFixtureExporter "$fixture_output"
java -cp "$fixture_classes" ResidualAndroidFixtureExporter "$fixture_output"
java -cp "$fixture_classes" UtilityAndroidFixtureExporter "$fixture_output"
java -cp "$fixture_classes" PrototypeAndroidFixtureExporter "$fixture_output"
java -cp "$fixture_classes" com.omnidev.workspace.domain.neural.ExpertTrustAndroidFixtureExporter "$fixture_output"
fixture_package="$fixture_output/java/com/omnidev/workspace/neural/probe"
mkdir -p "$fixture_package"
for checkpoint in TrainedTextIntentCheckpoint TrainedRefusalGateCheckpoint; do
  { echo 'package com.omnidev.workspace.neural.probe;'; cat "$fixture_classes/$checkpoint.java"; } > "$fixture_package/$checkpoint.java"
done
{ echo 'package com.omnidev.workspace.neural.probe;'; cat neural-core/checkpoints/bilingual-refusal-v1/TrainedBilingualRefusalCheckpoint.java; } \
  > "$fixture_package/TrainedBilingualRefusalCheckpoint.java"
for checkpoint in neural-core/checkpoints/rich-text-v1/*.java neural-core/checkpoints/public-refusal-v1/*.java neural-core/checkpoints/public-decision-v1/*.java neural-core/checkpoints/residual-adapter-v1/*.java neural-core/checkpoints/utility-expert-v2/*.java neural-core/checkpoints/prototype-support-v1/*.java neural-core/checkpoints/expert-trust-v1/*.java; do
  { echo 'package com.omnidev.workspace.neural.probe;'; cat "$checkpoint"; } > "$fixture_package/$(basename "$checkpoint")"
done
bash scripts/prepare_support_android.sh "$fixture_output"
python3 - "$fixture_output" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
paths = sorted((root / 'java').rglob('*.java')) + sorted((root / 'assets').glob('*.tsv'))
with (root / 'assets/provenance.txt').open('a') as output:
    for path in paths:
        output.write(f'{path.relative_to(root)} sha256={hashlib.sha256(path.read_bytes()).hexdigest()}\n')
PY
# Compile and exercise the exact packaged checkpoints/contract used by ART.
compile_java "${compat[@]}" -encoding UTF-8 -cp "$fixture_classes" -d "$fixture_classes" \
  "$fixture_package/"*.java neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/NeuralProbeContract.java \
  neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/PublicNeuralProbeContract.java \
  neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/ResidualNeuralProbeContract.java \
  neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/UtilityNeuralProbeContract.java \
  neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/PrototypeNeuralProbeContract.java \
  neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/ExpertTrustNeuralProbeContract.java \
  neural-core/smoke/AndroidProbeSmoke.java
java -cp "$fixture_classes" AndroidProbeSmoke "$fixture_output/assets/probes.tsv" "$fixture_output/assets/bilingual-cascade.tsv" "$fixture_output/assets/rich-presence.tsv" "$fixture_output/assets/public-probes.tsv" "$fixture_output/assets/residual-probes.tsv" "$fixture_output/assets/utility-probes.tsv" "$fixture_output/assets/prototype-probes.tsv" "$fixture_output/assets/trust-probes.tsv"

compile_java "${compat[@]}" -encoding UTF-8 -cp "$fixture_classes" -d "$fixture_classes"   "$fixture_output/java/com/omnidev/workspace/domain/neural/"*.java   neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/SupportRetrievalProbeContract.java   neural-core/smoke/SupportRetrievalAndroidSmoke.java
java -cp "$fixture_classes" SupportRetrievalAndroidSmoke "$fixture_output/assets"
