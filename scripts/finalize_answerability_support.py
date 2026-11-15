"""Record the original zero-veto result and fixed-head follow-up without hiding lost answers."""
import hashlib,json,sys
from pathlib import Path
from prepare_answerability_support import validate_followup
validate_followup()
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/answerability-support-v8';CHECK=ROOT/'neural-core/checkpoints/answerability-support-v8'
r=json.loads((OUT/'report.json').read_text());f=json.loads((OUT/'preservation-report.json').read_text());p=json.loads((OUT/'parity-retention.json').read_text())
g=dict(production_eligible=False,independent_human_labels=False,physical_device_tested=False,
       initial_reservation_commit='11bec65c7a8a28c0e041750751d78cc1dcc836fb',followup_reservation_commit='83686be1146e3843df851f15f88a8beab009d949',
       initial_policy_pipeline_gain=False,
       followup_wrong_reduced=f['old1488']['pipeline']['accepted_wrong']<f['old1488']['pipeline']['proposal_wrong'],
       followup_all_proposal_correct_preserved=not f['old1488']['lost_correct_ids'],
       followup_v7_stress_preserved=not f['v7_stress']['lost_correct_ids'],
       followup_outperformed_proposer_on_new48=False,
       old1488_observed=True,initial72_observed_before_followup=True,
       fresh48_is_targeted_assistant_authored=True,
       head_parameters=1025,head_raw_fp32_bytes=4100,
       head_size_excludes_proposer_and_indexes=True,
       default_policy='Frozen v1 remains exact. Both zero-veto and preservation follow-up are explicit experiments. Failed recall preservation prevents promotion; never use observed test labels to change the cutoff.')
paths=[]
for folder,pattern in [('neural-core/src/main/java','**/SupportAnswerabilityModel.java'),('neural-core/src/main/java','**/SupportPairEncoder.java'),('neural-core/src/test/java','**/SupportAnswerabilityModelTest.java'),('neural-core/training','**/Answerability*.java'),('neural-core/smoke','Answerability*.java'),('neural-core/fixtures/support-answerability-v8','*'),('neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe','Answerability*.java')]:
 paths += [str(x.relative_to(ROOT)) for x in (ROOT/folder).glob(pattern) if x.is_file()]
paths += ['knowledge/omni-product.jsonl','neural-core/checkpoints/subword-support-v7/TrainedSubwordSupport.java','neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java','neural-core/checkpoints/query-memory-v5/exemplars.tsv','neural-core/checkpoints/product-support-v1/TrainedProductSupport.java','scripts/train_answerability_support.sh','scripts/prepare_answerability_support.py','scripts/test_answerability_support.py','scripts/prepare_answerability_support_android.sh','scripts/finalize_answerability_support.py']
g['source_sha256']={s:hashlib.sha256((ROOT/s).read_bytes()).hexdigest() for s in sorted(paths)}
g['export_sha256']={s:hashlib.sha256((OUT/s).read_bytes()).hexdigest() for s in ('TrainedAnswerabilitySupport.java','TrainedAnswerabilityPreservation.java','expected.tsv','arm-expected.tsv','preservation-expected.tsv')}
(OUT/'quality-provenance.json').write_text(json.dumps(g,indent=2)+'\n')
if '--verify' in sys.argv:
 for name in ('report.json','selection.json','preservation-report.json','preservation-selection.json','parity-retention.json','quality-provenance.json'):
  if json.loads((OUT/name).read_text())!=json.loads((CHECK/name).read_text()):raise ValueError('Answerability reproduction drift: '+name)
print('Answerability provenance verified: original no-veto and failed preservation are retained; production remains false')
