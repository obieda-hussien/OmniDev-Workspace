#!/usr/bin/env bash
set -euo pipefail
support_output=$1
support_data=$(mktemp -d)
trap 'rm -rf "$support_data"' EXIT
python3 scripts/prepare_product_support.py "$support_data"
python3 scripts/prepare_ranked_support.py
mkdir -p "$support_output/assets" "$support_output/java/com/omnidev/workspace/domain/neural"
cp "$support_data/documents.tsv" "$support_output/assets/support-documents.tsv"
python3 - "$support_output" <<'PY'
import pathlib,sys
root=pathlib.Path(sys.argv[1])
a=pathlib.Path('neural-core/fixtures/product-support/queries.tsv').read_text()
b=pathlib.Path('neural-core/fixtures/product-support-v2/fresh.tsv').read_text().splitlines()[1:]
(root/'assets/support-queries.tsv').write_text(a+'\n'.join(b)+'\n')
PY
cp neural-core/checkpoints/product-support-v2/expected.tsv "$support_output/assets/support-probes.tsv"
cp neural-core/checkpoints/product-support-v1/TrainedProductSupport.java neural-core/checkpoints/product-support-v2/TrainedRankedSupport.java "$support_output/java/com/omnidev/workspace/domain/neural/"
