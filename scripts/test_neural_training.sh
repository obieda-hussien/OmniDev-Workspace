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
  neural-core/smoke/DecisionTrainingSmoke.java neural-core/smoke/TextCorpusValidationSmoke.java \
  neural-core/smoke/DecisionChallengeValidationSmoke.java
java -cp "$training_out" DecisionTrainingSmoke "$training_out/TrainedDecisionCheckpoint.java"
compile_java "${training_compat[@]}" -encoding UTF-8 -cp "$training_out" -d "$training_out" \
  "$training_out/TrainedDecisionCheckpoint.java" "$training_out/TrainedLargeDecisionCheckpoint.java"
java -cp "$training_out" DecisionTrainingSmoke --verify
java -cp "$training_out" TextCorpusValidationSmoke neural-core/fixtures/text-intents.tsv
java -cp "$training_out" com.omnidev.workspace.domain.neural.TextIntentExperiment \
  neural-core/fixtures/text-intents.tsv "$training_out/TrainedTextIntentCheckpoint.java"
compile_java "${training_compat[@]}" -encoding UTF-8 -cp "$training_out" -d "$training_out" \
  "$training_out/TrainedTextIntentCheckpoint.java"
java -cp "$training_out" com.omnidev.workspace.domain.neural.TextIntentExperiment \
  neural-core/fixtures/text-intents.tsv --verify
java -cp "$training_out" com.omnidev.workspace.domain.neural.DecisionChallengeExperiment \
  neural-core/fixtures/decision-challenges.tsv neural-core/fixtures/text-intents.tsv
java -cp "$training_out" DecisionChallengeValidationSmoke \
  neural-core/fixtures/decision-challenges.tsv neural-core/fixtures/text-intents.tsv
python3 scripts/import_clinc_rejection.py --verify neural-core/fixtures/clinc-subset
java -cp "$training_out" com.omnidev.workspace.domain.neural.RejectTrainingExperiment \
  neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv \
  "$training_out/TrainedRejectIntentCheckpoint.java" neural-core/fixtures/decision-challenges.tsv
compile_java "${training_compat[@]}" -encoding UTF-8 -cp "$training_out" -d "$training_out" "$training_out/TrainedRejectIntentCheckpoint.java"
java -cp "$training_out" com.omnidev.workspace.domain.neural.RejectTrainingExperiment \
  neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv \
  --verify neural-core/fixtures/decision-challenges.tsv
java -cp "$training_out" com.omnidev.workspace.domain.neural.GatedRejectionExperiment \
  neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv \
  "$training_out/TrainedRefusalGateCheckpoint.java" neural-core/fixtures/decision-challenges.tsv
compile_java "${training_compat[@]}" -encoding UTF-8 -cp "$training_out" -d "$training_out" "$training_out/TrainedRefusalGateCheckpoint.java"
java -cp "$training_out" com.omnidev.workspace.domain.neural.GatedRejectionExperiment \
  neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv \
  --verify neural-core/fixtures/decision-challenges.tsv
