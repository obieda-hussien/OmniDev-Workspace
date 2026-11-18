#!/usr/bin/env bash
set -euo pipefail
output=$1
python3 scripts/prepare_support_reliability.py
cp "$output/assets/memory-queries.tsv" "$output/assets/reliability-queries.tsv"
python3 - "$output/assets/reliability-queries.tsv" <<'PY'
import csv,pathlib,sys
with pathlib.Path('neural-core/fixtures/support-reliability-v6/fresh.tsv').open() as f:rows=list(csv.DictReader(f,delimiter='\t'))
with pathlib.Path(sys.argv[1]).open('a') as f:
 for q in rows:f.write('\t'.join([q['id'],'fresh',q['language'],q['primary'],q['secondary'],q['query']])+'\n')
PY
cp neural-core/checkpoints/support-reliability-v6/expected.tsv "$output/assets/reliability-probes.tsv"
cp neural-core/checkpoints/support-reliability-v6/TrainedSupportReliability.java "$output/java/com/omnidev/workspace/domain/neural/"
