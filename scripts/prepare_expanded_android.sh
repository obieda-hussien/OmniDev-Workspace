#!/usr/bin/env bash
set -euo pipefail
expanded_output=$1
python3 scripts/prepare_support_data_v4.py
mkdir -p "$expanded_output/assets" "$expanded_output/java/com/omnidev/workspace/domain/neural"
python3 - "$expanded_output" <<'PY'
import csv,pathlib,sys
root=pathlib.Path(sys.argv[1]);rows=[]
for name in ['product-support/queries.tsv','product-support-v2/fresh.tsv']:
 with pathlib.Path('neural-core/fixtures',name).open() as source:
  for q in csv.DictReader(source,delimiter='\t'):rows.append([q['id'],q['split'],q['language'],q['expected_topic'],'-',q['query']])
for name in ['hybrid-support-v3/fresh.tsv','support-data-v4/queries.tsv']:
 with pathlib.Path('neural-core/fixtures',name).open() as source:
  for q in csv.DictReader(source,delimiter='\t'):rows.append([q['id'],q.get('split','fresh'),q['language'],q['primary'],q['secondary'],q['query']])
assert len(rows)==1101
(root/'assets/expanded-queries.tsv').write_text('id\tsplit\tlanguage\tprimary\tsecondary\tquery\n'+'\n'.join('\t'.join(q) for q in rows)+'\n')
PY
cp neural-core/checkpoints/support-data-v4/expected.tsv "$expanded_output/assets/expanded-probes.tsv"
cp neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java "$expanded_output/java/com/omnidev/workspace/domain/neural/"
