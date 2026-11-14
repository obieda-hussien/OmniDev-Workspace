"""Pin the fusion experiment and preserve quality limitations instead of promoting on test scores."""
import hashlib,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/hybrid-support-v3'
r=json.loads((OUT/'report.json').read_text());fresh=r['new_authored'];languages=r['new_languages']
gates=dict(production_eligible=False,independent_human_labels=False,physical_device_tested=False,
 top1_not_below_lexical=fresh['hybrid_candidate']['top1_correct']>=fresh['lexical']['top1_correct'],
 all_primary_at3_not_below_lexical=fresh['hybrid_candidate']['all_primary_at3']>=fresh['lexical']['all_primary_at3'],
 accepted_correct_not_below_retained=fresh['hybrid_candidate']['accepted_correct']>=fresh['v1_guarded']['accepted_correct'],
 every_language_top1_not_below_lexical=all(x['hybrid']['top1_correct']>=x['lexical']['top1_correct'] for x in languages.values()),
 default_policy='Retain frozen v1 support guard; field-fusion weights are explicit experimental candidates only.')
paths=[ROOT/p for p in ['knowledge/omni-product.jsonl','neural-core/fixtures/product-support/queries.tsv','neural-core/fixtures/product-support/protocol.json','neural-core/fixtures/product-support-v2/fresh.tsv','neural-core/fixtures/hybrid-support-v3/fresh.tsv','neural-core/fixtures/hybrid-support-v3/protocol.json','neural-core/src/main/java/com/omnidev/workspace/domain/neural/HybridSupportRetriever.java','neural-core/src/main/java/com/omnidev/workspace/domain/neural/ProductSupportModel.java','neural-core/src/main/java/com/omnidev/workspace/domain/neural/TinyNeuralRuntime.java','neural-core/training/com/omnidev/workspace/domain/neural/HybridSupportExperiment.java','neural-core/training/com/omnidev/workspace/domain/neural/ProductSupportExperiment.java','neural-core/checkpoints/product-support-v1/TrainedProductSupport.java','neural-core/checkpoints/product-support-v2/TrainedRankedSupport.java','scripts/prepare_hybrid_support.py','neural-core/smoke/HybridSupportTrainingSmoke.java','neural-core/smoke/HybridSupportCheckpointSmoke.java','neural-core/smoke/HybridSupportAndroidSmoke.java','neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/HybridSupportProbeContract.java','neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/HybridSupportAndroidTest.java','scripts/prepare_hybrid_android.sh','scripts/train_hybrid_support.sh']]+[OUT/'TrainedHybridSupport.java',OUT/'expected.tsv']
gates['artifact_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
(OUT/'quality-provenance.json').write_text(json.dumps(gates,indent=2)+'\n')
if '--verify' in sys.argv:
 for name in ['report.json','selection.json','quality-provenance.json','parity-retention.json']:
  if json.loads((OUT/name).read_text())!=json.loads((ROOT/'neural-core/checkpoints/hybrid-support-v3'/name).read_text()):raise ValueError('Fusion report drift: '+name)
print('Hybrid quality/provenance verified; readiness remains false')
