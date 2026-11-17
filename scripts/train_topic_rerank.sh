#!/usr/bin/env bash
set -euo pipefail
compat=(--release 8)
if [[ "${1:-}" == --source-target-only ]]; then compat=(-source 8 -target 8 -Xlint:-options)
elif [[ $# != 0 ]]; then exit 2; fi
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
compiler=(javac)
if ! command -v javac >/dev/null; then compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
out=build/topic-rerank
mkdir -p "$out"
python3 scripts/test_topic_rerank.py
python3 scripts/prepare_product_support.py build/product-support
python3 scripts/prepare_query_memory.py build/query-memory-v5
"${compiler[@]}" "${compat[@]}" -encoding UTF-8 -d "$classes" \
  neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java \
  neural-core/training/com/omnidev/workspace/domain/neural/*.java \
  neural-core/checkpoints/{product-support-v1,support-data-v4,query-memory-v5,subword-support-v7,contrastive-support-v10}/Trained*.java \
  neural-core/smoke/TopicRerankFeatures.java
java -Xmx768m -cp "$classes" com.omnidev.workspace.domain.neural.TopicRerankFeatures "$out/features.tsv"
python3 scripts/fit_topic_rerank.py "$out/features.tsv" "$out/report.json"
cmp neural-core/checkpoints/topic-rerank-v11/report.json "$out/report.json"
python3 -m unittest discover -s scripts -p 'test_refusal_rescue.py'
java -Xmx768m -cp "$classes" com.omnidev.workspace.domain.neural.TopicRerankFeatures "$out/rescue-features.tsv" --rescue
python3 scripts/fit_refusal_rescue.py "$out/rescue-features.tsv" "$out/rescue-report.json"
cmp neural-core/checkpoints/refusal-rescue-v12/report.json "$out/rescue-report.json"
