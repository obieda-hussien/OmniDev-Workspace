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
python3 scripts/reserve_utility_eval.py --verify
compile_java "${compat[@]}" -encoding UTF-8 -d "$out" neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java neural-core/training/com/omnidev/workspace/domain/neural/*.java
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/smoke/UtilityTrainingContractSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.UtilityTrainingContractSmoke
java -cp "$out" com.omnidev.workspace.domain.neural.TextIntentExperiment neural-core/fixtures/text-intents.tsv "$out/TrainedTextIntentCheckpoint.java" > "$out/domain.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedTextIntentCheckpoint.java"
java -cp "$out" com.omnidev.workspace.domain.neural.GatedRejectionExperiment neural-core/fixtures/text-intents.tsv neural-core/fixtures/clinc-subset/rejection.tsv "$out/TrainedRefusalGateCheckpoint.java" neural-core/fixtures/decision-challenges.tsv > "$out/prior.json"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedRefusalGateCheckpoint.java" neural-core/checkpoints/rich-text-v1/*.java neural-core/checkpoints/public-refusal-v1/*.java neural-core/checkpoints/public-decision-v1/*.java
sed 's/TrainedUtilityExpert/TrainedUtilityV1Reference/g' neural-core/checkpoints/utility-expert-v1/TrainedUtilityExpert.java > "$out/TrainedUtilityV1Reference.java"
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" "$out/TrainedUtilityV1Reference.java"
if [[ "$verify_artifacts" != true ]]; then
  java -cp "$out" com.omnidev.workspace.domain.neural.UtilityExpertExperiment build/utility-expert-v2
fi
compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" build/utility-expert-v2/*.java neural-core/smoke/UtilityExpertCheckpointSmoke.java
java -cp "$out" com.omnidev.workspace.domain.neural.UtilityExpertCheckpointSmoke build/utility-expert-v2/expected.tsv
if [[ -d neural-core/checkpoints/utility-expert-v2 ]]; then
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$out" -d "$out" neural-core/checkpoints/utility-expert-v2/*.java
  java -cp "$out" com.omnidev.workspace.domain.neural.UtilityExpertCheckpointSmoke build/utility-expert-v2/expected.tsv
fi
python3 - <<'PYMETA'
import hashlib,json,pathlib
p=pathlib.Path('build/utility-expert-v2');r=json.loads((p/'report.json').read_text())
paths=list(p.glob('*.java'))+list(pathlib.Path('neural-core/checkpoints/rich-text-v1').glob('*.java'))+list(pathlib.Path('neural-core/checkpoints/public-refusal-v1').glob('*.java'))+list(pathlib.Path('neural-core/checkpoints/public-decision-v1').glob('*.java'))+list(pathlib.Path('neural-core/checkpoints/utility-expert-v1').glob('*.java'))
paths += [pathlib.Path('neural-core/fixtures/utility-expert')/n for n in ['protocol.json','rehearsal-protocol.json','fresh.tsv','provenance.json']]
paths += [pathlib.Path(n) for n in ['neural-core/fixtures/text-intents.tsv','neural-core/fixtures/clinc-subset/rejection.tsv','neural-core/fixtures/bilingual-refusal/corpus.tsv','neural-core/fixtures/massive-refusal/corpus.tsv']]
r['artifact_sha256']={str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in paths}
r['observed_known_retention_passed']=r['evaluations']['all_observed_holdout']['utility']['previous_correct_lost']==0
r['promotion_blocker']=str(r['evaluations']['all_observed_holdout']['utility']['previous_correct_lost'])+' earlier correct held-out cases lost; all v2 tests already observed; independent v2 positive and physical-device validation absent.'
(p/'report.json').write_text(json.dumps(r,indent=2)+'\n')
PYMETA
