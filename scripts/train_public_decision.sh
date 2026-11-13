#!/usr/bin/env bash
set -euo pipefail
compat=(--release 8)
verify_artifacts=false
for option in "$@"; do
  case "$option" in --source-target-only) compat=(-source 8 -target 8 -Xlint:-options) ;; --verify-artifacts) verify_artifacts=true ;; *) echo 'Unknown option' >&2; exit 2 ;; esac
done
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
compile_java() { if command -v javac >/dev/null 2>&1; then javac "$@"; else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi; }
python3 scripts/prepare_massive_refusal.py --verify
compile_java "${compat[@]}" -encoding UTF-8 -d "$out" neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java neural-core/training/com/omnidev/workspace/domain/neural/*.java
java -cp "$out" com.omnidev.workspace.domain.neural.TextIntentExperiment neural-core/fixtures/text-intents.tsv "$out/TrainedTextIntentCheckpoint.java" > "$out/domain.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedTextIntentCheckpoint.java"
java -cp "$out" com.omnidev.workspace.domain.neural.GatedRejectionExperiment neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv "$out/TrainedRefusalGateCheckpoint.java" neural-core/fixtures/decision-challenges.tsv > "$out/prior.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedRefusalGateCheckpoint.java" neural-core/checkpoints/rich-text-v1/*.java
if [[ "$verify_artifacts" != true ]]; then
  java -cp "$out" com.omnidev.workspace.domain.neural.PublicDecisionExperiment neural-core/fixtures/massive-refusal/corpus.tsv build/public-decision
fi
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" build/public-decision/*.java neural-core/smoke/PublicDecisionCheckpointSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.PublicDecisionCheckpointSmoke neural-core/fixtures/massive-refusal/corpus.tsv build/public-decision/expected.tsv
if [[ -d neural-core/checkpoints/public-decision-v1 ]]; then
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/public-decision-v1/*.java
  java -cp "$out" com.omnidev.workspace.domain.neural.PublicDecisionCheckpointSmoke neural-core/fixtures/massive-refusal/corpus.tsv build/public-decision/expected.tsv
fi
python3 - <<'PY'
import hashlib, json, pathlib
p = pathlib.Path('build/public-decision'); r = json.loads((p/'report.json').read_text())
paths = list(p.glob('*.java')) + [pathlib.Path('neural-core/fixtures/massive-refusal')/n for n in ['corpus.tsv','decision-protocol.json','provenance.json']]
r['artifact_sha256'] = {str(x): hashlib.sha256(x.read_bytes()).hexdigest() for x in paths}
r['observed_known_retention_passed'] = all(x['public_decision']['previous_correct_lost'] == 0 for x in r['evaluations'].values())
(p/'report.json').write_text(json.dumps(r, indent=2)+'\n')
PY
