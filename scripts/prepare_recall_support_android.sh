#!/usr/bin/env bash
set -euo pipefail
output=$1
python3 scripts/prepare_recall_support.py
cp "$output/assets/answerability-queries.tsv" "$output/assets/recall-queries.tsv"
python3 - "$output/assets/recall-queries.tsv" <<'PY'
import csv,pathlib,sys
with pathlib.Path('neural-core/fixtures/support-recall-v9/fresh.tsv').open() as source, pathlib.Path(sys.argv[1]).open('a') as out:
 for q in csv.DictReader(source,delimiter='\t'):out.write('\t'.join([q['id'],q['split'],q['language'],q['topic'] if q['label']=='1' else '', '',q['query']])+'\n')
PY
cp neural-core/checkpoints/recall-support-v9/expected.tsv "$output/assets/recall-probes.tsv"
cp neural-core/checkpoints/recall-support-v9/TrainedRecallAwareSupport.java "$output/java/com/omnidev/workspace/domain/neural/"
