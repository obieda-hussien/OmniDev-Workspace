#!/usr/bin/env bash
set -euo pipefail
output=$1
python3 scripts/prepare_answerability_support.py
cp "$output/assets/subword-queries.tsv" "$output/assets/answerability-queries.tsv"
python3 - "$output/assets/answerability-queries.tsv" <<'PY'
import csv,pathlib,sys
with pathlib.Path(sys.argv[1]).open('a') as out:
 for name in ('contrast','fresh','followup'):
  with pathlib.Path('neural-core/fixtures/support-answerability-v8/'+name+'.tsv').open() as f:
   for q in csv.DictReader(f,delimiter='\t'):out.write('\t'.join([q['id'],q['split'],q['language'],q['topic'] if q['label']=='1' else '', '',q['query']])+'\n')
PY
cp neural-core/checkpoints/answerability-support-v8/preservation-expected.tsv "$output/assets/answerability-probes.tsv"
cp neural-core/checkpoints/answerability-support-v8/TrainedAnswerability*.java "$output/java/com/omnidev/workspace/domain/neural/"
