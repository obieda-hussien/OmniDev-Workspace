#!/usr/bin/env bash
set -euo pipefail
compat=(--release 8)
if [[ "${1:-}" == --source-target-only ]]; then compat=(-source 8 -target 8 -Xlint:-options)
elif [[ $# != 0 ]]; then exit 2; fi
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
compile_java(){ if command -v javac >/dev/null 2>&1; then javac "$@"; else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi; }
python3 scripts/prepare_product_support.py build/product-support
python3 scripts/prepare_query_memory.py build/query-memory-v5
python3 scripts/test_answerability_support.py
mkdir -p build/answerability-support-v8
compile_java "${compat[@]}" -encoding UTF-8 -d "$classes" \
  neural-core/src/main/java/com/omnidev/workspace/domain/neural/*.java \
  neural-core/training/com/omnidev/workspace/domain/neural/*.java \
  neural-core/checkpoints/product-support-v1/TrainedProductSupport.java \
  neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java \
  neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java \
  neural-core/checkpoints/subword-support-v7/TrainedSubwordSupport.java \
  neural-core/smoke/SupportReliabilityTrainingSmoke.java \
  neural-core/smoke/AnswerabilityTrainingSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.AnswerabilityTrainingSmoke
java -cp "$classes" com.omnidev.workspace.domain.neural.AnswerabilitySupportExperiment build/answerability-support-v8 > build/answerability-support-v8/fit.log
python3 - <<'PY'
import hashlib,json,pathlib
p=json.load(open('neural-core/fixtures/support-answerability-v8/followup-protocol.json'));o=pathlib.Path('build/answerability-support-v8')
for name,key in [('TrainedAnswerabilitySupport.java','initial_selected_source_sha256'),('selection.json','initial_selection_sha256')]:
 if hashlib.sha256((o/name).read_bytes()).hexdigest()!=p[key]:raise ValueError('Reserved fixed-head follow-up identity drift')
PY
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" build/answerability-support-v8/TrainedAnswerabilitySupport.java
java -cp "$classes" com.omnidev.workspace.domain.neural.AnswerabilityPreservationExperiment build/answerability-support-v8 > build/answerability-support-v8/preservation-fit.log
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" \
  build/answerability-support-v8/TrainedAnswerabilityPreservation.java neural-core/smoke/AnswerabilityCheckpointSmoke.java
java -cp "$classes" com.omnidev.workspace.domain.neural.AnswerabilityCheckpointSmoke build/answerability-support-v8
if [[ -f neural-core/checkpoints/answerability-support-v8/TrainedAnswerabilitySupport.java ]]; then
  for file in TrainedAnswerabilitySupport.java TrainedAnswerabilityPreservation.java expected.tsv arm-expected.tsv preservation-expected.tsv; do
    cmp "build/answerability-support-v8/$file" "neural-core/checkpoints/answerability-support-v8/$file"
  done
  compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" neural-core/checkpoints/answerability-support-v8/TrainedAnswerability*.java
  java -cp "$classes" com.omnidev.workspace.domain.neural.AnswerabilityCheckpointSmoke build/answerability-support-v8
  python3 scripts/finalize_answerability_support.py --verify
else
  python3 scripts/finalize_answerability_support.py
fi
compile_java "${compat[@]}" -encoding UTF-8 -cp "$classes" -d "$classes" neural-core/smoke/AnswerabilitySupportBenchmark.java
java -cp "$classes" com.omnidev.workspace.domain.neural.AnswerabilitySupportBenchmark build/answerability-support-v8
