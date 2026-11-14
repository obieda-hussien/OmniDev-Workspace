#!/usr/bin/env bash
set -euo pipefail
memory_output=$1
python3 scripts/prepare_query_memory.py "$memory_output/memory-verified"
mkdir -p "$memory_output/assets" "$memory_output/java/com/omnidev/workspace/domain/neural"
cp "$memory_output/assets/expanded-queries.tsv" "$memory_output/assets/memory-queries.tsv"
python3 - "$memory_output/assets/memory-queries.tsv" <<'PY'
import csv,pathlib,sys
with pathlib.Path('neural-core/fixtures/query-memory-v5/fresh.tsv').open() as f:rows=list(csv.DictReader(f,delimiter='\t'))
with pathlib.Path(sys.argv[1]).open('a') as f:
 for q in rows:f.write('\t'.join([q['id'],'fresh',q['language'],q['primary'],q['secondary'],q['query']])+'\n')
PY
cp "$memory_output/memory-verified/exemplars.tsv" "$memory_output/assets/memory-exemplars.tsv"
cp neural-core/checkpoints/query-memory-v5/expected.tsv "$memory_output/assets/memory-probes.tsv"
cp neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java "$memory_output/java/com/omnidev/workspace/domain/neural/"
