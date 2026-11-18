"""Keep no-loss counts, modest new challenge gains and every failure/provenance boundary."""
import hashlib,json,sys
from pathlib import Path
from prepare_recall_support import validate
validate()
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/recall-support-v9';CHECK=ROOT/'neural-core/checkpoints/recall-support-v9'
r=json.loads((OUT/'report.json').read_text());p=json.loads((OUT/'parity-retention.json').read_text());old=r['observed1782']['pipeline'];f=r['fresh90']['pipeline']['pipeline']
g=dict(production_eligible=False,independent_human_labels=False,physical_device_tested=False,
 reservation_commit='e464d68d95e2e32525af613869f8aebacfce712f',
 selected_positive_weight=r['selection']['selected']['positive_weight'],
 observed_correct_preserved=old['lost_proposal_correct']==0,
 observed_wrong_reduced=old['accepted_wrong']<old['proposal_wrong'],
 fresh_correct_preserved=f['lost_proposal_correct']==0,
 fresh_wrong_reduced=f['accepted_wrong']<f['proposal_wrong'],
 v7_stress_correct_preserved=not r['v7_stress']['lost_correct_ids'],
 guard_includes_fitting_data=True,guard_is_unbiased_risk_bound=False,
 head_parameters=1025,raw_fp32_head_bytes=4100,
 head_size_excludes_proposer_and_indexes=True,
 fresh90_is_targeted_assistant_authored=True,
 default_policy='Exact v1 retained. Recall guard includes TRAIN and DEV; preservation here is an observed engineering result, not population accuracy, a calibrated risk bound or a deployment approval. New90 never select head or threshold; all original labels and remaining errors are preserved.')
paths=[]
for folder,pattern in [('neural-core/training','**/RecallAware*.java'),('neural-core/smoke','RecallAware*.java'),('neural-core/fixtures/support-recall-v9','*'),('neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe','RecallAware*.java')]:paths += [str(x.relative_to(ROOT)) for x in (ROOT/folder).glob(pattern) if x.is_file()]
paths+=['knowledge/omni-product.jsonl','neural-core/src/main/java/com/omnidev/workspace/domain/neural/SupportAnswerabilityModel.java','neural-core/src/main/java/com/omnidev/workspace/domain/neural/SupportPairEncoder.java','neural-core/training/com/omnidev/workspace/domain/neural/AnswerabilityTrainer.java','neural-core/training/com/omnidev/workspace/domain/neural/AnswerabilitySupportExperiment.java','neural-core/checkpoints/answerability-support-v8/TrainedAnswerabilitySupport.java','neural-core/checkpoints/subword-support-v7/TrainedSubwordSupport.java','scripts/train_recall_support.sh','scripts/prepare_recall_support.py','scripts/test_recall_support.py','scripts/prepare_recall_support_android.sh','scripts/finalize_recall_support.py']
g['source_sha256']={s:hashlib.sha256((ROOT/s).read_bytes()).hexdigest() for s in sorted(paths)}
g['export_sha256']={s:hashlib.sha256((OUT/s).read_bytes()).hexdigest() for s in ('TrainedRecallAwareSupport.java','expected.tsv','arm-expected.tsv','policy-expected.tsv')}
(OUT/'quality-provenance.json').write_text(json.dumps(g,indent=2)+'\n')
if '--verify' in sys.argv:
 for name in ('report.json','selection.json','parity-retention.json','quality-provenance.json'):
  if json.loads((OUT/name).read_text())!=json.loads((CHECK/name).read_text()):raise ValueError('Recall reproduction drift: '+name)
print('Recall experiment provenance verified; no population/device or production claims')
