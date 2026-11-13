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
python3 scripts/prepare_bilingual_refusal.py --verify
python3 scripts/prepare_fresh_intent_eval.py --verify
compile_java "${compat[@]}" -encoding UTF-8 -d "$out" neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java neural-core/training/com/omnidev/workspace/domain/neural/*.java neural-core/smoke/RichTrainingContractSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.RichTrainingContractSmoke
java -cp "$out" com.omnidev.workspace.domain.neural.TextIntentExperiment neural-core/fixtures/text-intents.tsv "$out/TrainedTextIntentCheckpoint.java" > "$out/domain.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedTextIntentCheckpoint.java"
java -cp "$out" com.omnidev.workspace.domain.neural.GatedRejectionExperiment neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv "$out/TrainedRefusalGateCheckpoint.java" neural-core/fixtures/decision-challenges.tsv > "$out/prior.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedRefusalGateCheckpoint.java" neural-core/checkpoints/multi-intent-v1/*.java
if [[ "$verify_artifacts" != true ]]; then
  java -cp "$out" com.omnidev.workspace.domain.neural.RichTextExperiment neural-core/fixtures/bilingual-refusal/corpus.tsv neural-core/fixtures/rich-text/fresh-english.tsv build/rich-text
fi
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" build/rich-text/*.java neural-core/smoke/RichTextCheckpointSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.RichTextCheckpointSmoke neural-core/fixtures/bilingual-refusal/corpus.tsv neural-core/fixtures/rich-text/fresh-english.tsv build/rich-text/expected.tsv build/rich-text/expected-scores.tsv
if [[ -d neural-core/checkpoints/rich-text-v1 ]]; then
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/rich-text-v1/*.java
  java -cp "$out" com.omnidev.workspace.domain.neural.RichTextCheckpointSmoke neural-core/fixtures/bilingual-refusal/corpus.tsv neural-core/fixtures/rich-text/fresh-english.tsv build/rich-text/expected.tsv build/rich-text/expected-scores.tsv
fi
python3 - <<'PY'
import hashlib, json, pathlib
directory = pathlib.Path('build/rich-text')
report = json.loads((directory / 'report.json').read_text())
paths = list(directory.glob('*.java')) + [pathlib.Path('neural-core/fixtures/bilingual-refusal/rich-text-protocol.json'),
    pathlib.Path('neural-core/fixtures/rich-text/fresh-english.tsv'), pathlib.Path('neural-core/fixtures/bilingual-refusal/corpus.tsv')]
report['artifact_sha256'] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
evaluations = report['evaluations']
report['observed_known_retention_passed'] = all(group['rich_fused']['known_accepted'] == group['prior']['known_accepted'] and
    group['rich_fused']['accepted_correct'] == group['prior']['accepted_correct'] for group in evaluations.values())
(directory / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
PY
