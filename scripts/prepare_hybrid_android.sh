#!/usr/bin/env bash
set -euo pipefail
hybrid_output=$1
python3 scripts/prepare_hybrid_support.py
mkdir -p "$hybrid_output/assets" "$hybrid_output/java/com/omnidev/workspace/domain/neural"
python3 - "$hybrid_output" <<'PY'
import csv,pathlib,sys
root=pathlib.Path(sys.argv[1]);rows=[]
for name in ['product-support/queries.tsv','product-support-v2/fresh.tsv']:
 with pathlib.Path('neural-core/fixtures',name).open() as source:
  for q in csv.DictReader(source,delimiter='\t'):rows.append([q['id'],q['split'],q['language'],q['expected_topic'],'-',q['query']])
with pathlib.Path('neural-core/fixtures/hybrid-support-v3/fresh.tsv').open() as source:
 for q in csv.DictReader(source,delimiter='\t'):rows.append([q['id'],'fresh',q['language'],q['primary'],q['secondary'],q['query']])
assert len(rows)==459
(root/'assets/hybrid-queries.tsv').write_text('id\tsplit\tlanguage\tprimary\tsecondary\tquery\n'+'\n'.join('\t'.join(q) for q in rows)+'\n')
PY
cp neural-core/checkpoints/hybrid-support-v3/expected.tsv "$hybrid_output/assets/hybrid-probes.tsv"
cp neural-core/checkpoints/hybrid-support-v3/TrainedHybridSupport.java "$hybrid_output/java/com/omnidev/workspace/domain/neural/"
