#!/usr/bin/env bash
set -euo pipefail
output=$1
python3 scripts/prepare_contrastive_support.py
cp "$output/assets/recall-queries.tsv" "$output/assets/contrastive-queries.tsv"
python3 - "$output/assets/contrastive-queries.tsv" <<'PY'
import csv,pathlib,sys
with pathlib.Path('neural-core/fixtures/support-contrastive-v10/fresh.tsv').open() as source, pathlib.Path(sys.argv[1]).open('a') as out:
 for q in csv.DictReader(source,delimiter='\t'):out.write('\t'.join([q['id'],q['split'],q['language'],q['topic'] if q['label']=='1' else '', '',q['query']])+'\n')
PY
cp neural-core/checkpoints/contrastive-support-v10/expected.tsv "$output/assets/contrastive-probes.tsv"
cp neural-core/checkpoints/contrastive-support-v10/arm-expected.tsv "$output/assets/contrastive-arms.tsv"
cp neural-core/checkpoints/contrastive-support-v10/TrainedContrastiveSupport.java "$output/java/com/omnidev/workspace/domain/neural/"
