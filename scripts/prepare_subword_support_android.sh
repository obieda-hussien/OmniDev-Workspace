#!/usr/bin/env bash
set -euo pipefail
output=$1
python3 scripts/prepare_subword_support.py
cp "$output/assets/reliability-queries.tsv" "$output/assets/subword-queries.tsv"
python3 - "$output/assets/subword-queries.tsv" <<'PY'
import csv,pathlib,sys
with pathlib.Path('neural-core/fixtures/support-subword-v7/fresh.tsv').open() as f:rows=list(csv.DictReader(f,delimiter='\t'))
with pathlib.Path(sys.argv[1]).open('a') as f:
 for q in rows:f.write('\t'.join([q['id'],'fresh',q['language'],q['primary'],q['secondary'],q['query']])+'\n')
PY
cp neural-core/checkpoints/subword-support-v7/expected.tsv "$output/assets/subword-probes.tsv"
cp neural-core/checkpoints/subword-support-v7/TrainedSubwordSupport.java "$output/java/com/omnidev/workspace/domain/neural/"
