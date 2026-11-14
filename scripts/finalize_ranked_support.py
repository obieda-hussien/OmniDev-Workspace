"""Pin the new training/evaluation artifacts while retaining failed readiness gates."""
import hashlib
import json
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/product-support-v2'
report=json.loads((OUT/'report.json').read_text())
fresh=report['fresh_authored_wordings']
gates=dict(production_eligible=False,independent_human_labels=False,physical_device_tested=False,
    raw_top1_not_below_v1=fresh['v2_raw']['recall_at_1']>=fresh['v1_raw']['recall_at_1'],
    raw_top1_not_below_lexical=fresh['v2_raw']['recall_at_1']>=fresh['lexical']['recall_at_1'],
    candidate_guard_errors_not_above_v1_guard=fresh['v2_guarded']['accepted_wrong']<=fresh['v1_guarded']['accepted_wrong'],
    default_policy='Retain frozen v1 guard; candidate weights require explicit experimental constructors.')
paths=[ROOT/p for p in [
    'knowledge/omni-product.jsonl','neural-core/fixtures/product-support/queries.tsv',
    'neural-core/fixtures/product-support/protocol.json','neural-core/fixtures/product-support-v2/fresh.tsv',
    'neural-core/fixtures/product-support-v2/protocol.json','neural-core/checkpoints/product-support-v1/TrainedProductSupport.java',
    'neural-core/src/main/java/com/omnidev/workspace/domain/neural/ProductSupportModel.java',
    'neural-core/src/main/java/com/omnidev/workspace/domain/neural/TinyNeuralRuntime.java',
    'neural-core/src/main/java/com/omnidev/workspace/domain/neural/OmniRamLite.java',
    'neural-core/src/main/java/com/omnidev/workspace/domain/neural/BilingualTextEncoder.java',
    'neural-core/training/com/omnidev/workspace/domain/neural/ProductSupportExperiment.java',
    'neural-core/training/com/omnidev/workspace/domain/neural/RankedSupportTrainer.java',
    'neural-core/training/com/omnidev/workspace/domain/neural/RankedSupportExperiment.java',
    'neural-core/smoke/RankedSupportTrainingSmoke.java','neural-core/smoke/RankedSupportCheckpointSmoke.java',
    'neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/SupportRetrievalProbeContract.java',
    'scripts/prepare_ranked_support.py','scripts/train_ranked_support.sh',
]]+[OUT/'TrainedRankedSupport.java',OUT/'expected.tsv']
gates['artifact_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(OUT/'quality-provenance.json').write_text(json.dumps(gates,indent=2)+'\n')
if '--verify' in sys.argv:
    committed=ROOT/'neural-core/checkpoints/product-support-v2'
    for name in ['selection.json','report.json','quality-provenance.json','retention.json']:
        if json.loads((OUT/name).read_text())!=json.loads((committed/name).read_text()):raise ValueError('Ranking experiment drift: '+name)
print('Ranked support provenance checked; production eligibility remains false')
