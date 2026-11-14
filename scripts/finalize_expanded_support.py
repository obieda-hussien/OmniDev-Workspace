"""Expose every quality gate and pin data, representation, training and compiled probes."""
import hashlib,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/support-data-v4'
r=json.loads((OUT/'report.json').read_text());f=r['fresh_authored'];langs=r['languages']
g=dict(production_eligible=False,independent_human_labels=False,physical_device_tested=False,
 top1_not_below_lexical=f['expanded']['top1_correct']>=f['lexical']['top1_correct'],
 top1_not_below_matched_old_only=f['expanded']['top1_correct']>=f['matched_old_only']['top1_correct'],
 all_primary_at3_not_below_lexical=f['expanded']['all_primary_at3']>=f['lexical']['all_primary_at3'],
 accepted_correct_not_below_retained=f['expanded']['accepted_correct']>=f['retained_default']['accepted_correct'],
 accepted_wrong_not_above_retained=f['expanded']['accepted_wrong']<=f['retained_default']['accepted_wrong'],
 every_language_top1_not_below_lexical=all(x['expanded']['top1_correct']>=x['lexical']['top1_correct'] for x in langs.values()),
 default_policy='Keep frozen v1 guard; explicit v4 candidate only, no normal APK activation.')
paths=['knowledge/omni-product.jsonl','neural-core/fixtures/support-data-v4/queries.tsv','neural-core/fixtures/support-data-v4/protocol.json','neural-core/fixtures/product-support/queries.tsv','neural-core/fixtures/product-support-v2/fresh.tsv','neural-core/fixtures/hybrid-support-v3/fresh.tsv','neural-core/src/main/java/com/omnidev/workspace/domain/neural/HybridSupportRetriever.java','neural-core/src/main/java/com/omnidev/workspace/domain/neural/ProductSupportModel.java','neural-core/src/main/java/com/omnidev/workspace/domain/neural/TinyNeuralRuntime.java','neural-core/training/com/omnidev/workspace/domain/neural/HybridSupportExperiment.java','neural-core/training/com/omnidev/workspace/domain/neural/ExpandedSupportExperiment.java','neural-core/checkpoints/product-support-v1/TrainedProductSupport.java','neural-core/checkpoints/hybrid-support-v3/TrainedHybridSupport.java','scripts/prepare_support_data_v4.py','scripts/train_expanded_support.sh','neural-core/smoke/ExpandedSupportCheckpointSmoke.java','neural-core/smoke/ExpandedSupportAndroidSmoke.java','neural-core/smoke/ExpandedSupportTrainingSmoke.java','neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/ExpandedSupportProbeContract.java','neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/ExpandedSupportAndroidTest.java','scripts/prepare_expanded_android.sh']
g['sha256']={p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in paths}
g['export_sha256']={p:hashlib.sha256((OUT/p).read_bytes()).hexdigest() for p in ['TrainedExpandedSupport.java','expected.tsv']}
(OUT/'quality-provenance.json').write_text(json.dumps(g,indent=2)+'\n')
if '--verify' in sys.argv:
 for p in ['report.json','selection.json','parity-retention.json','quality-provenance.json']:
  if json.loads((OUT/p).read_text())!=json.loads((ROOT/'neural-core/checkpoints/support-data-v4'/p).read_text()):raise ValueError('Expanded-data report drift: '+p)
print('Expanded-data quality/provenance verified; readiness remains false')
