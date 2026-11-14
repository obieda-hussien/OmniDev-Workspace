"""Record experimental provenance and negative quality outcomes; not a promotion gate."""
import hashlib
import json
from pathlib import Path
import sys

root=Path(__file__).resolve().parents[1]
out=root/'build/product-support'
report=json.loads((out/'report.json').read_text())
paths=[root/p for p in [
    'knowledge/omni-product.jsonl','docs/OMNI_PRODUCT_KNOWLEDGE.md',
    'neural-core/fixtures/product-support/queries.tsv','neural-core/fixtures/product-support/protocol.json',
    'neural-core/src/main/java/com/omnidev/workspace/domain/neural/ProductSupportModel.java',
    'neural-core/src/main/java/com/omnidev/workspace/domain/neural/TinyNeuralRuntime.java',
    'neural-core/src/main/java/com/omnidev/workspace/domain/neural/OmniRamLite.java',
    'neural-core/src/main/java/com/omnidev/workspace/domain/neural/BilingualTextEncoder.java',
    'neural-core/training/com/omnidev/workspace/domain/neural/ProductSupportExperiment.java',
]]+[out/'TrainedProductSupport.java',out/'expected.tsv']
provenance=dict(production_eligible=False,
    raw_retrieval_regressed=report['held_out_learned']['recall_at_1']<report['held_out_baseline']['recall_at_1'],
    guard_is_post_hoc_diagnostic=True,independent_validation_required=True,
    artifact_sha256={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths})
(out/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
if '--verify' in sys.argv:
    committed=root/'neural-core/checkpoints/product-support-v1'
    for name in ['report.json','guard-diagnostic.json','provenance.json']:
        if json.loads((out/name).read_text())!=json.loads((committed/name).read_text()):
            raise ValueError('Experimental report/artifact drift: '+name)
print('Support provenance verified; experimental production eligibility remains false')
