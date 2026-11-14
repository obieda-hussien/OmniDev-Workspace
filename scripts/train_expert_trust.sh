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
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/prototype-support-v1/*.java
python3 scripts/reserve_trust_eval.py --verify
if [[ "$verify_artifacts" != true ]]; then
  java -cp "$out" com.omnidev.workspace.domain.neural.ExpertTrustExperiment build/expert-trust
fi
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" build/expert-trust/*.java
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/smoke/ExpertTrustTrainingSmoke.java neural-core/smoke/ExpertTrustCheckpointSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.ExpertTrustTrainingSmoke build/expert-trust/training-controls.json
java -cp "$out" com.omnidev.workspace.domain.neural.ExpertTrustCheckpointSmoke build/expert-trust/expected.tsv
if [[ -d neural-core/checkpoints/expert-trust-v1 ]]; then
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/expert-trust-v1/*.java
  java -cp "$out" com.omnidev.workspace.domain.neural.ExpertTrustCheckpointSmoke build/expert-trust/expected.tsv
fi
python3 - <<'PYMETA'
import hashlib,json,pathlib
out=pathlib.Path('build/expert-trust');r=json.loads((out/'report.json').read_text())
r['training_controls']=json.loads((out/'training-controls.json').read_text())
paths=list(out.glob('*.java'))
for folder in ['rich-text-v1','public-refusal-v1','utility-expert-v2','prototype-support-v1']:
    paths+=list(pathlib.Path('neural-core/checkpoints',folder).glob('*.java'))
paths+=list(pathlib.Path('neural-core/fixtures/expert-trust').glob('*'))
paths+=[pathlib.Path('neural-core/fixtures')/p for p in ['text-intents.tsv','clinc-subset/rejection.tsv','bilingual-refusal/corpus.tsv','massive-refusal/corpus.tsv','rich-text/fresh-english.tsv','decision-challenges.tsv','residual-adapter/fresh.tsv','utility-expert/fresh.tsv','prototype-support/authored.tsv']]
r['artifact_sha256']={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
r['observed_retention_passed']=r['evaluations']['observed_holdout']['trust']['previous_correct_lost']==0
r['promotion_blocker']=str(r['evaluations']['observed_holdout']['trust']['previous_correct_lost'])+' earlier-correct observed cases lost; no new supported or mixed-language positives; physical-device validation absent.'
(out/'report.json').write_text(json.dumps(r,indent=2)+'\n')
PYMETA
