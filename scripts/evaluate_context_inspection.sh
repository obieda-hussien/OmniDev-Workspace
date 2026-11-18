#!/usr/bin/env bash
set -euo pipefail
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
for checkpoint in public-decision-v1/TrainedPublicDecision public-refusal-v1/TrainedPublicRefusal rich-text-v1/TrainedRichVocabulary; do
    { echo 'package com.omnidev.workspace.domain.neural.bundled;'; cat "neural-core/checkpoints/$checkpoint.java"; } > "$out/${checkpoint##*/}.java"
done
cat > "$out/Evaluate.java" <<'JAVA'
package com.omnidev.workspace.domain.engine;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

public class Evaluate {
    public static void main(String[] args) throws Exception {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (String row : Files.readAllLines(Paths.get(args[0]))) {
            String[] cells = row.split("\t", 3);
            if (cells[0].equals("split") || cells[0].equals("train")) continue;
            int[] matrix = counts.computeIfAbsent(cells[0], key -> new int[4]);
            boolean supported = BundledDecisionModels.supportsInspection(cells[2]);
            matrix[(cells[1].equals("1") ? 0 : 2) + (supported ? 0 : 1)]++;
        }
        counts.forEach((split, m) -> System.out.printf("%s TP=%d FN=%d FP=%d TN=%d%n",
                split, m[0], m[1], m[2], m[3]));
    }
}
JAVA
if command -v javac >/dev/null; then compiler=(javac); else compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$out" \
    neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java \
    app/src/main/java/com/omnidev/workspace/domain/engine/BundledDecisionModels.java "$out"/*.java
java -cp "$out" com.omnidev.workspace.domain.engine.Evaluate neural-core/fixtures/context-inspection/examples.tsv
