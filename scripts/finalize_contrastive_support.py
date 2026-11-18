"""Preserve the balanced classification gain without claiming a pipeline or production gain."""
import hashlib,json,sys
from pathlib import Path
from prepare_contrastive_support import validate
validate()
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/contrastive-support-v10';CHECK=ROOT/'neural-core/checkpoints/contrastive-support-v10'
r=json.loads((OUT/'report.json').read_text());p=json.loads((OUT/'parity-retention.json').read_text());f=r['fresh96']['paired'];old=r['observed1872']['pipeline']
g=dict(production_eligible=False,independent_human_labels=False,physical_device_tested=False,
 reservation_commit='937de8b8261ace830fd1343760789a91814c0a65',
 selected_hidden=r['selection']['selected']['hidden'],selected_ranking_weight=r['selection']['selected']['fit']['ranking_weight'],
 fresh_pair_classification_above_v9_weighted=f['correct_at_half']>r['v9_selected_pairs']['correct_at_half'],
 fresh_pair_classification_above_v9_unit=f['correct_at_half']>r['v9_unit_pairs']['correct_at_half'],
 fresh_pipeline_wrong_below_v9=r['fresh96']['pipeline']['pipeline']['accepted_wrong']<r['v9_fresh']['pipeline']['accepted_wrong'],
 observed_correct_preserved=old['lost_proposal_correct']==0,
 fresh_correct_preserved=not r['fresh96']['pipeline']['lost_correct_ids'],
 v7_stress_correct_preserved=not r['v7_stress']['lost_correct_ids'],
 guard_includes_fitting_data=True,guard_is_unbiased_risk_bound=False,
 selected_head_parameters=p['selected_parameters'],selected_raw_fp32_head_bytes=4*p['selected_parameters'],
 head_size_excludes_proposer_and_indexes=True,fresh96_is_targeted_assistant_authored=True,
 default_policy='Exact v1 retained. Selected by DEV NLL before new96 scoring. Balanced explicit-reference classification and order are separate from actual proposal acceptance. Guard includes fitting data, the matched linear arm can win a fresh pipeline metric, and no population/device/semantic-LLM or production claim follows.')
paths=[]
for folder,pattern in [('neural-core/training','**/ContrastiveSupport*.java'),('neural-core/smoke','ContrastiveSupport*.java'),('neural-core/fixtures/support-contrastive-v10','*'),('neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe','ContrastiveSupport*.java'),('neural-core/src/main/java','**/SupportContrastiveModel.java'),('neural-core/src/test/java','**/SupportContrastiveModelTest.java')]:paths += [str(x.relative_to(ROOT)) for x in (ROOT/folder).glob(pattern) if x.is_file()]
paths+=['knowledge/omni-product.jsonl','neural-core/src/main/java/com/omnidev/workspace/domain/neural/SupportPairEncoder.java','neural-core/training/com/omnidev/workspace/domain/neural/AnswerabilitySupportExperiment.java','neural-core/training/com/omnidev/workspace/domain/neural/AnswerabilityTrainer.java','neural-core/checkpoints/recall-support-v9/TrainedRecallAwareSupport.java','neural-core/checkpoints/subword-support-v7/TrainedSubwordSupport.java','scripts/train_contrastive_support.sh','scripts/prepare_contrastive_support.py','scripts/test_contrastive_support.py','scripts/prepare_contrastive_support_android.sh','scripts/finalize_contrastive_support.py']
g['source_sha256']={s:hashlib.sha256((ROOT/s).read_bytes()).hexdigest() for s in sorted(paths)}
g['export_sha256']={s:hashlib.sha256((OUT/s).read_bytes()).hexdigest() for s in ('TrainedContrastiveSupport.java','expected.tsv','arm-expected.tsv','policy-expected.tsv')}
(OUT/'quality-provenance.json').write_text(json.dumps(g,indent=2)+'\n')
if '--verify' in sys.argv:
 for name in ('report.json','selection.json','parity-retention.json','quality-provenance.json'):
  if json.loads((OUT/name).read_text())!=json.loads((CHECK/name).read_text()):raise ValueError('Contrastive reproduction drift: '+name)
print('Contrastive provenance verified; balanced pair gain is not a fresh pipeline improvement')
