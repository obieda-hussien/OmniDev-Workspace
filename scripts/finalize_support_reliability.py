"""Pin actual selector weights/evaluations and expose the failed promotion requirements."""
import hashlib
import json
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/support-reliability-v6'
r=json.loads((OUT/'report.json').read_text())
p=json.loads((OUT/'parity-retention.json').read_text())
fresh=r['fresh_authored']
g=dict(production_eligible=False,independent_human_labels=False,physical_device_tested=False,
       fresh_correct_improved=fresh['selected']['accepted_correct']>fresh['v5']['accepted_correct'],
       fresh_wrong_not_increased=fresh['selected']['accepted_wrong']<=fresh['v5']['accepted_wrong'],
       observed_wrong_not_increased=r['observed_regressions']['selected']['accepted_wrong']<=r['observed_regressions']['v5']['accepted_wrong'],
       all_prior_v5_correct_preserved=not p['v5_previously_correct_lost_ids'],
       all_retained_default_correct_preserved=not p['retained_default_previously_correct_lost_ids'],
       default_policy='Retain frozen v1; learned replacement and veto are explicit experimental diagnostics only.',
       calibration_note='One selector score underlies all policy controls. selector_NLL/Brier/ECE are not confidence metrics for the v5 relevance score.',
       reservation_commit='9b6afa6ad84a352715b1186ec4805ffb2e50080a')
paths=['knowledge/omni-product.jsonl','neural-core/fixtures/support-reliability-v6/fresh.tsv',
       'neural-core/fixtures/support-reliability-v6/protocol.json',
       'neural-core/fixtures/product-support/queries.tsv','neural-core/fixtures/support-data-v4/queries.tsv',
       'neural-core/checkpoints/query-memory-v5/exemplars.tsv','neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java',
       'neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java',
       'neural-core/checkpoints/product-support-v1/TrainedProductSupport.java',
       'neural-core/src/main/java/com/omnidev/workspace/domain/neural/SupportReliabilityModel.java',
       'neural-core/src/main/java/com/omnidev/workspace/domain/neural/SupportQueryMemory.java',
       'neural-core/src/main/java/com/omnidev/workspace/domain/neural/HybridSupportRetriever.java',
       'neural-core/src/main/java/com/omnidev/workspace/domain/neural/TinyNeuralRuntime.java',
       'neural-core/training/com/omnidev/workspace/domain/neural/SupportReliabilityExperiment.java',
       'neural-core/training/com/omnidev/workspace/domain/neural/ProductSupportExperiment.java',
       'neural-core/src/test/java/com/omnidev/workspace/domain/neural/SupportReliabilityModelTest.java',
       'neural-core/smoke/SupportReliabilityTrainingSmoke.java','neural-core/smoke/SupportReliabilityCheckpointSmoke.java',
       'neural-core/smoke/SupportReliabilityAndroidSmoke.java','scripts/prepare_support_reliability.py',
       'scripts/test_support_reliability.py','scripts/prepare_support_reliability_android.sh',
       'scripts/train_support_reliability.sh',
       'neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/SupportReliabilityProbeContract.java',
       'neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/SupportReliabilityAndroidTest.java']
g['source_sha256']={path:hashlib.sha256((ROOT/path).read_bytes()).hexdigest() for path in paths}
g['export_sha256']={name:hashlib.sha256((OUT/name).read_bytes()).hexdigest() for name in ('TrainedSupportReliability.java','expected.tsv','risk-coverage.tsv')}
(OUT/'quality-provenance.json').write_text(json.dumps(g,indent=2)+'\n')
if '--verify' in sys.argv:
    for name in ('report.json','selection.json','parity-retention.json','quality-provenance.json'):
        if json.loads((OUT/name).read_text())!=json.loads((ROOT/'neural-core/checkpoints/support-reliability-v6'/name).read_text()):
            raise ValueError('Selector reproduction drift: '+name)
print('Selector provenance verified; observed retention/error gates prevent promotion')
