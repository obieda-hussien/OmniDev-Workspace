#!/usr/bin/env bash
set -euo pipefail
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
if command -v javac >/dev/null; then compiler=(javac); else compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$out" \
    app/src/main/java/com/omnidev/workspace/domain/history/{ChatMetadataModel,OpeningTitleWeights}.java \
    neural-core/smoke/OpeningTitleEvaluation.java
java -cp "$out" OpeningTitleEvaluation neural-core/fixtures/opening-titles/pairs.tsv
