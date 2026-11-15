"""Record the representation gain without turning finite authored results into production claims."""
import hashlib,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/query-memory-v5'
r=json.loads((OUT/'report.json').read_text());f=r['fresh_authored'];o=r['observed1101']
g=dict(production_eligible=False,independent_human_labels=False,physical_device_tested=False,
 selected_head_is_fitted=r['selection']['selected']['name']=='learned',
 top1_not_below_frozen_v4=f['query_memory']['top1_correct']>=f['frozen_v4']['top1_correct'],
 every_language_top1_not_below_frozen_v4=all(v['query_memory']['top1_correct']>=v['frozen_v4']['top1_correct'] for v in r['languages'].values()),
 accepted_wrong_not_above_retained=f['query_memory']['accepted_wrong']<=f['retained_default']['accepted_wrong'],
 observed_wrong_not_above_retained=o['query_memory']['accepted_wrong']<=o['retained_default']['accepted_wrong'],
 default_policy='Retain frozen v1 guard; explicit query-memory candidate only. TRAIN exemplar text is a ranking hint, never returned evidence.')
paths=['knowledge/omni-product.jsonl','neural-core/fixtures/product-support/queries.tsv','neural-core/fixtures/support-data-v4/queries.tsv','neural-core/fixtures/query-memory-v5/fresh.tsv','neural-core/fixtures/query-memory-v5/protocol.json','neural-core/src/main/java/com/omnidev/workspace/domain/neural/SupportQueryMemory.java','neural-core/src/main/java/com/omnidev/workspace/domain/neural/HybridSupportRetriever.java','neural-core/src/main/java/com/omnidev/workspace/domain/neural/TinyNeuralRuntime.java','neural-core/training/com/omnidev/workspace/domain/neural/QueryMemoryExperiment.java','neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java','neural-core/checkpoints/product-support-v1/TrainedProductSupport.java','scripts/prepare_query_memory.py','scripts/train_query_memory.sh','neural-core/smoke/QueryMemoryTrainingSmoke.java','neural-core/smoke/QueryMemoryCheckpointSmoke.java','neural-core/smoke/QueryMemoryAndroidSmoke.java','neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/QueryMemoryProbeContract.java','neural-android/src/androidTest/java/com/omnidev/workspace/neural/probe/QueryMemoryAndroidTest.java','scripts/prepare_query_memory_android.sh']
paths += ['neural-core/smoke/QueryMemoryRuntimeAudit.java', 'neural-core/smoke/QueryMemoryFootprintAgent.java', 'neural-core/src/test/java/com/omnidev/workspace/domain/neural/SupportQueryMemoryTest.java', 'scripts/audit_query_memory_runtime.sh']
g['source_sha256']={p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in paths}
g['export_sha256']={p:hashlib.sha256((OUT/p).read_bytes()).hexdigest() for p in ['TrainedQueryMemory.java','exemplars.tsv','expected.tsv']}
(OUT/'quality-provenance.json').write_text(json.dumps(g,indent=2)+'\n')
if '--verify' in sys.argv:
 for name in ['report.json','selection.json','parity-retention.json','quality-provenance.json']:
  if json.loads((OUT/name).read_text())!=json.loads((ROOT/'neural-core/checkpoints/query-memory-v5'/name).read_text()):raise ValueError('Memory reproduction drift: '+name)
print('Query-memory provenance verified; production eligibility remains false')
