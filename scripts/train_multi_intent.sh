#!/usr/bin/env bash
set -euo pipefail
compat=(--release 8)
verify_artifacts=false
for option in "$@"; do
  case "$option" in
    --source-target-only) compat=(-source 8 -target 8 -Xlint:-options) ;;
    --verify-artifacts) verify_artifacts=true ;;
    *) echo 'Usage: train_multi_intent.sh [--source-target-only] [--verify-artifacts]' >&2; exit 2 ;;
  esac
done
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
compile_java() { if command -v javac >/dev/null 2>&1; then javac "$@"; else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi; }
python3 scripts/prepare_bilingual_refusal.py --verify
compile_java "${compat[@]}" -encoding UTF-8 -d "$out" neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java neural-core/training/com/omnidev/workspace/domain/neural/*.java
java -cp "$out" com.omnidev.workspace.domain.neural.TextIntentExperiment neural-core/fixtures/text-intents.tsv "$out/TrainedTextIntentCheckpoint.java" > "$out/domain.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedTextIntentCheckpoint.java"
java -cp "$out" com.omnidev.workspace.domain.neural.GatedRejectionExperiment neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv "$out/TrainedRefusalGateCheckpoint.java" neural-core/fixtures/decision-challenges.tsv > "$out/prior.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedRefusalGateCheckpoint.java"
if [[ "$verify_artifacts" != true ]]; then
  java -cp "$out" com.omnidev.workspace.domain.neural.MultiIntentExperiment neural-core/fixtures/bilingual-refusal/corpus.tsv build/multi-intent
fi
python3 - <<'PY'
import hashlib, json, pathlib
directory = pathlib.Path('build/multi-intent')
report = json.loads((directory / 'report.json').read_text())
rows = [f'threshold\t{report["threshold"]}', f'require_match\t{str(report["require_match"]).lower()}']
rows += [f'{head["label"]}\t{float(head["temperature"])}' for head in report['heads']]
(directory / 'metadata.tsv').write_text('\n'.join(rows) + '\n')
report['corpus_sha256'] = hashlib.sha256(pathlib.Path('neural-core/fixtures/bilingual-refusal/corpus.tsv').read_bytes()).hexdigest()
report['protocol_sha256'] = hashlib.sha256(pathlib.Path('neural-core/fixtures/bilingual-refusal/multi-intent-protocol.json').read_bytes()).hexdigest()
report['checkpoint_sha256'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(directory.glob('*.java'))}
(directory / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
PY
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" build/multi-intent/*.java neural-core/smoke/MultiIntentCheckpointSmoke.java
java -cp "$out" MultiIntentCheckpointSmoke neural-core/fixtures/bilingual-refusal/corpus.tsv build/multi-intent/expected.tsv build/multi-intent/metadata.tsv
if [[ -d neural-core/checkpoints/multi-intent-v1 ]]; then
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/multi-intent-v1/*.java
  java -cp "$out" MultiIntentCheckpointSmoke neural-core/fixtures/bilingual-refusal/corpus.tsv build/multi-intent/expected.tsv build/multi-intent/metadata.tsv
fi
