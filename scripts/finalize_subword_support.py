"""Preserve the targeted robustness gain, matched-arm outcome and all retention failures."""
import hashlib,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/subword-support-v7'
r=json.loads((OUT/'report.json').read_text());p=json.loads((OUT/'parity-retention.json').read_text());f=r['fresh_stress']
g=dict(production_eligible=False,independent_human_labels=False,physical_device_tested=False,
       fresh_is_targeted_authored_stress=True,augmentation_creates_independent_examples=False,
       selected_arm=r['selection']['selected']['arm'],
       stress_accepted_correct_improved=f['selected']['accepted_correct']>f['v6']['accepted_correct'],
       stress_wrong_not_increased=f['selected']['accepted_wrong']<=f['v6']['accepted_wrong'],
       each_language_correct_not_below_v6=all(v['selected']['accepted_correct']>=v['v6']['accepted_correct'] for v in r['languages'].values()),
       observed_wrong_not_increased=r['observed_regressions']['selected']['accepted_wrong']<=r['observed_regressions']['v6']['accepted_wrong'],
       all_prior_v5_correct_preserved=not p['v5_previously_correct_lost_ids'],
       all_retained_default_correct_preserved=not p['retained_default_previously_correct_lost_ids'],
       reservation_commit='2de419ffbf3a17f773ef61bb941f419171f2610b',
       default_policy='Frozen v1 remains exact; selected and matched-arm subword models are explicit experiments only. Do not switch arms based on observed stress scores.')
paths=['knowledge/omni-product.jsonl','neural-core/fixtures/support-subword-v7/fresh.tsv','neural-core/fixtures/support-subword-v7/protocol.json',
       'neural-core/fixtures/product-support/queries.tsv','neural-core/fixtures/support-data-v4/queries.tsv',
       'neural-core/checkpoints/query-memory-v5/exemplars.tsv','neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java',
       'neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java','neural-core/checkpoints/product-support-v1/TrainedProductSupport.java',
       'neural-core/checkpoints/support-reliability-v6/TrainedSupportReliability.java',
       'neural-core/src/main/java/com/omnidev/workspace/domain/neural/TrainTokenCoverage.java',
       'neural-core/src/main/java/com/omnidev/workspace/domain/neural/SupportSubwordModel.java',
       'neural-core/src/main/java/com/omnidev/workspace/domain/neural/SupportQueryMemory.java',
       'neural-core/src/main/java/com/omnidev/workspace/domain/neural/HybridSupportRetriever.java',
       'neural-core/training/com/omnidev/workspace/domain/neural/SubwordSupportExperiment.java',
       'neural-core/training/com/omnidev/workspace/domain/neural/SupportReliabilityExperiment.java',
       'neural-core/training/com/omnidev/workspace/domain/neural/ProductSupportExperiment.java',
       'neural-core/src/test/java/com/omnidev/workspace/domain/neural/TrainTokenCoverageTest.java',
       'neural-core/src/test/java/com/omnidev/workspace/domain/neural/SupportSubwordModelTest.java',
       'neural-core/smoke/SubwordSupportTrainingSmoke.java','neural-core/smoke/SubwordSupportCheckpointSmoke.java',
       'neural-core/smoke/SubwordSupportAndroidSmoke.java','scripts/prepare_subword_support.py','scripts/test_subword_support.py',
       'scripts/prepare_subword_support_android.sh','scripts/train_subword_support.sh',
       'neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/SubwordSupportProbeContract.java',
       'neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/SubwordSupportAndroidTest.java']
g['source_sha256']={s:hashlib.sha256((ROOT/s).read_bytes()).hexdigest() for s in paths}
g['export_sha256']={s:hashlib.sha256((OUT/s).read_bytes()).hexdigest() for s in ('TrainedSubwordSupport.java','expected.tsv','arm-expected.tsv')}
(OUT/'quality-provenance.json').write_text(json.dumps(g,indent=2)+'\n')
if '--verify' in sys.argv:
 for name in ('report.json','selection.json','parity-retention.json','quality-provenance.json'):
  if json.loads((OUT/name).read_text())!=json.loads((ROOT/'neural-core/checkpoints/subword-support-v7'/name).read_text()):raise ValueError('Subword reproduction drift: '+name)
print('Subword sources/quality verified; targeted stress improvement does not override failed regression gates')
