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
python3 scripts/import_clinc_rejection.py --verify neural-core/fixtures/clinc-subset
python3 scripts/prepare_bilingual_refusal.py --verify
python3 scripts/prepare_massive_refusal.py --verify
python3 scripts/reserve_residual_eval.py --verify
python3 scripts/reserve_utility_eval.py --verify
compile_java "${compat[@]}" -encoding UTF-8 -d "$out" neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java neural-core/training/com/omnidev/workspace/domain/neural/*.java
java -cp "$out" com.omnidev.workspace.domain.neural.TextIntentExperiment neural-core/fixtures/text-intents.tsv "$out/TrainedTextIntentCheckpoint.java" > "$out/domain.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedTextIntentCheckpoint.java"
java -cp "$out" com.omnidev.workspace.domain.neural.GatedRejectionExperiment neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv "$out/TrainedRefusalGateCheckpoint.java" neural-core/fixtures/decision-challenges.tsv > "$out/prior.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedRefusalGateCheckpoint.java" neural-core/checkpoints/rich-text-v1/*.java neural-core/checkpoints/public-refusal-v1/*.java neural-core/checkpoints/public-decision-v1/*.java
python3 scripts/prepare_prototype_challenges.py --verify
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/utility-expert-v2/*.java
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/smoke/PrototypeTrainingContractSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.PrototypeTrainingContractSmoke
if [[ "$verify_artifacts" != true ]]; then
  java -cp "$out" com.omnidev.workspace.domain.neural.PrototypeSupportExperiment build/prototype-support
fi
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" build/prototype-support/*.java neural-core/smoke/PrototypeCheckpointSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.PrototypeCheckpointSmoke build/prototype-support/expected.tsv
if [[ -d neural-core/checkpoints/prototype-support-v1 ]]; then
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/prototype-support-v1/*.java
  java -cp "$out" com.omnidev.workspace.domain.neural.PrototypeCheckpointSmoke build/prototype-support/expected.tsv
fi
python3 - <<'PYMETA'
import hashlib,json,pathlib
p=pathlib.Path('build/prototype-support');r=json.loads((p/'report.json').read_text())
paths=list(p.glob('*.java'))+list(pathlib.Path('neural-core/checkpoints/utility-expert-v2').glob('*.java'))+list(pathlib.Path('neural-core/checkpoints/rich-text-v1').glob('*.java'))+list(pathlib.Path('neural-core/checkpoints/public-refusal-v1').glob('*.java'))
paths += list(pathlib.Path('neural-core/fixtures/prototype-support').glob('*'))+[pathlib.Path('neural-core/fixtures/bilingual-refusal/corpus.tsv'),pathlib.Path('neural-core/fixtures/massive-refusal/corpus.tsv')]
paths += [pathlib.Path(n) for n in ['neural-core/fixtures/text-intents.tsv','neural-core/fixtures/clinc-subset/rejection.tsv','neural-core/fixtures/rich-text/fresh-english.tsv','neural-core/fixtures/decision-challenges.tsv','neural-core/fixtures/residual-adapter/fresh.tsv','neural-core/fixtures/utility-expert/fresh.tsv']]
r['artifact_sha256']={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in paths if x.is_file()}
r['old_observed_retention_passed']=r['evaluations']['old_observed']['prototype']['previous_correct_lost']==0
r['authored_retention_passed']=all(v['prototype']['previous_correct_lost']==0 for k,v in r['evaluations'].items() if k.startswith('authored_'))
r['all_known_retention_passed']=r['old_observed_retention_passed'] and r['authored_retention_passed']
r['promotion_blocker']='Earlier-correct losses old/authored: '+str(r['evaluations']['old_observed']['prototype']['previous_correct_lost'])+'/'+str(sum(v['prototype']['previous_correct_lost'] for k,v in r['evaluations'].items() if k.startswith('authored_')))+'; independent traffic and physical-device validation absent.'
(p/'report.json').write_text(json.dumps(r,indent=2)+'\n')
PYMETA
