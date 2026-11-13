#!/usr/bin/env bash
set -euo pipefail
training_compat=(--release 8)
if [[ "${1:-}" == --source-target-only ]]; then training_compat=(-source 8 -target 8 -Xlint:-options)
elif [[ -n "${1:-}" ]]; then echo 'Usage: train_bilingual_refusal.sh [--source-target-only]' >&2; exit 2; fi
training_out=$(mktemp -d)
trap 'rm -rf "$training_out"' EXIT
compile_java() {
  if command -v javac >/dev/null 2>&1; then javac "$@"
  else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi
}
python3 scripts/prepare_bilingual_refusal.py --verify
compile_java "${training_compat[@]}" -encoding UTF-8 -d "$training_out" \
  neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java \
  neural-core/training/com/omnidev/workspace/domain/neural/*.java \
  neural-core/smoke/BilingualCorpusValidationSmoke.java neural-core/smoke/EarlyStoppingSmoke.java
java -cp "$training_out" com.omnidev.workspace.domain.neural.BilingualCorpusValidationSmoke neural-core/fixtures/bilingual-refusal/corpus.tsv
java -cp "$training_out" EarlyStoppingSmoke
java -cp "$training_out" com.omnidev.workspace.domain.neural.TextIntentExperiment \
  neural-core/fixtures/text-intents.tsv "$training_out/TrainedTextIntentCheckpoint.java" > "$training_out/domain-report.json"
compile_java "${training_compat[@]}" -encoding UTF-8 -cp "$training_out" -d "$training_out" "$training_out/TrainedTextIntentCheckpoint.java"
java -cp "$training_out" com.omnidev.workspace.domain.neural.GatedRejectionExperiment \
  neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv \
  "$training_out/TrainedRefusalGateCheckpoint.java" neural-core/fixtures/decision-challenges.tsv > "$training_out/prior-report.json"
compile_java "${training_compat[@]}" -encoding UTF-8 -cp "$training_out" -d "$training_out" "$training_out/TrainedRefusalGateCheckpoint.java"
training_artifacts=build/bilingual-refusal-v1
mkdir -p "$training_artifacts"
java -cp "$training_out" com.omnidev.workspace.domain.neural.BilingualRefusalExperiment \
  neural-core/fixtures/bilingual-refusal/corpus.tsv "$training_artifacts/TrainedBilingualRefusalCheckpoint.java" \
  "$training_artifacts/report.json"
compile_java "${training_compat[@]}" -encoding UTF-8 -cp "$training_out" -d "$training_out" \
  "$training_artifacts/TrainedBilingualRefusalCheckpoint.java"
java -cp "$training_out" com.omnidev.workspace.domain.neural.BilingualRefusalExperiment \
  neural-core/fixtures/bilingual-refusal/corpus.tsv --verify "$training_out/verification-report.json" > "$training_out/reload.log"
cmp "$training_artifacts/report.json" "$training_out/verification-report.json"
python3 - "$training_artifacts/TrainedBilingualRefusalCheckpoint.java" "$training_out/TrainedBilingualRefusalReplica.java" <<'PY'
import pathlib, sys
source = pathlib.Path(sys.argv[1]).read_text()
assert source.count('public final class TrainedBilingualRefusalCheckpoint {') == 1
pathlib.Path(sys.argv[2]).write_text(source.replace('public final class TrainedBilingualRefusalCheckpoint {', 'public final class TrainedBilingualRefusalReplica {'))
PY
compile_java "${training_compat[@]}" -encoding UTF-8 -cp "$training_out" -d "$training_out" \
  "$training_out/TrainedBilingualRefusalReplica.java" \
  neural-core/checkpoints/bilingual-refusal-v1/TrainedBilingualRefusalCheckpoint.java neural-core/smoke/BilingualCheckpointSmoke.java
java -cp "$training_out" BilingualCheckpointSmoke neural-core/fixtures/bilingual-refusal/corpus.tsv
echo 'Six-candidate fitting, deterministic refit, compiled checkpoint reload and frozen-head invariants passed.'
