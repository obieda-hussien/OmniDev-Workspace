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
python3 scripts/reserve_residual_eval.py --verify
compile_java "${compat[@]}" -encoding UTF-8 -d "$out" neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java neural-core/training/com/omnidev/workspace/domain/neural/*.java
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/smoke/ResidualTrainingContractSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.ResidualTrainingContractSmoke
java -cp "$out" com.omnidev.workspace.domain.neural.TextIntentExperiment neural-core/fixtures/text-intents.tsv "$out/TrainedTextIntentCheckpoint.java" > "$out/domain.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedTextIntentCheckpoint.java"
java -cp "$out" com.omnidev.workspace.domain.neural.GatedRejectionExperiment neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv "$out/TrainedRefusalGateCheckpoint.java" neural-core/fixtures/decision-challenges.tsv > "$out/prior.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedRefusalGateCheckpoint.java" neural-core/checkpoints/rich-text-v1/*.java neural-core/checkpoints/public-refusal-v1/*.java
if [[ "$verify_artifacts" != true ]]; then
  java -cp "$out" com.omnidev.workspace.domain.neural.ResidualAdapterExperiment build/residual-adapter
fi
python3 - <<'PY'
import hashlib,json,pathlib
p = pathlib.Path('build/residual-adapter'); report=json.loads((p/'report.json').read_text())
paths=list(p.glob('*.java')) + list(pathlib.Path('neural-core/checkpoints/rich-text-v1').glob('*.java')) + list(pathlib.Path('neural-core/checkpoints/public-refusal-v1').glob('*.java'))
paths += [pathlib.Path(x) for x in ['neural-core/fixtures/residual-adapter/protocol.json','neural-core/fixtures/residual-adapter/fresh.tsv','neural-core/fixtures/residual-adapter/provenance.json','neural-core/fixtures/bilingual-refusal/corpus.tsv','neural-core/fixtures/massive-refusal/corpus.tsv','neural-core/fixtures/text-intents.tsv','neural-core/fixtures/clinc-subset/rejection.tsv']]
report['artifact_sha256']={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in paths}
report['observed_known_retention_passed']=all(v['residual']['previous_correct_lost']==0 for v in report['evaluations'].values())
report['adapter_active']=report['gain']>0
report['quality_improvement_demonstrated']=False
(p/'report.json').write_text(json.dumps(report,indent=2)+'\n')
PY
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" build/residual-adapter/*.java neural-core/smoke/ResidualAdapterCheckpointSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.ResidualAdapterCheckpointSmoke neural-core/fixtures/massive-refusal/corpus.tsv build/residual-adapter/expected.tsv
if [[ -d neural-core/checkpoints/residual-adapter-v1 ]]; then
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/residual-adapter-v1/*.java
  java -cp "$out" com.omnidev.workspace.domain.neural.ResidualAdapterCheckpointSmoke neural-core/fixtures/massive-refusal/corpus.tsv build/residual-adapter/expected.tsv
fi
