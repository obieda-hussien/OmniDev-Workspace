#!/usr/bin/env bash
set -euo pipefail
compat=(--release 8)
if [[ "${1:-}" == --source-target-only ]]; then compat=(-source 8 -target 8 -Xlint:-options)
elif [[ $# != 0 ]]; then exit 2; fi
baseline=cce17b7545c00168ac99cc7245f362b15b3b124c
output=build/query-memory-runtime-audit
runtime_path=neural-core/src/main/java/com/omnidev/workspace/domain/neural/SupportQueryMemory.java
mkdir -p "$output/baseline" "$output/compact"
git show "$baseline:$runtime_path" > "$output/baseline/SupportQueryMemory.java"
python3 scripts/prepare_product_support.py build/product-support
python3 scripts/prepare_query_memory.py build/query-memory-v5
compile_java(){ if command -v javac >/dev/null 2>&1; then javac "$@"; else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi; }
sources=()
# Keep the audited graph fixed as later independent specialists add new API dependencies.
while IFS= read -r source; do
  if [[ "$source" == *.java && "$source" != "$runtime_path" ]]; then sources+=("$source"); fi
done < <(git ls-tree -r --name-only "$baseline" -- neural-core/src/main/java)
training_sources=()
while IFS= read -r source; do
  if [[ "$source" == *.java ]]; then training_sources+=("$source"); fi
done < <(git ls-tree -r --name-only "$baseline" -- neural-core/training)
for arm in baseline compact; do
  classes="$output/$arm/classes"
  mkdir -p "$classes"
  runtime="$runtime_path"
  if [[ "$arm" == baseline ]]; then runtime="$output/baseline/SupportQueryMemory.java"; fi
  compile_java "${compat[@]}" -encoding UTF-8 -d "$classes" "${sources[@]}" "$runtime" \
    "${training_sources[@]}" \
    neural-core/checkpoints/product-support-v1/TrainedProductSupport.java \
    neural-core/checkpoints/support-data-v4/TrainedExpandedSupport.java \
    neural-core/checkpoints/query-memory-v5/TrainedQueryMemory.java \
    neural-core/smoke/QueryMemoryRuntimeAudit.java neural-core/smoke/QueryMemoryFootprintAgent.java
  java -cp "$classes" com.omnidev.workspace.domain.neural.QueryMemoryRuntimeAudit "$output/$arm/audit.json"
  python3 - "$classes" "$output/$arm/agent.jar" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(sys.argv[2],'w') as z:
 z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: com.omnidev.workspace.domain.neural.QueryMemoryFootprintAgent\n\n')
 for p in root.glob('**/QueryMemoryFootprintAgent*.class'):z.write(p,str(p.relative_to(root)))
PY
  java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
    -javaagent:"$output/$arm/agent.jar" -cp "$classes" \
    com.omnidev.workspace.domain.neural.QueryMemoryFootprintAgent "$output/$arm/footprint.json"
done
python3 - "$output" "$baseline" "$runtime_path" <<'PY'
import hashlib,json,pathlib,platform,subprocess,sys
root=pathlib.Path(sys.argv[1]);arms={}
for arm in ('baseline','compact'):
 arms[arm]={name:json.loads((root/arm/(name+'.json')).read_text()) for name in ('audit','footprint')}
a,b=(arms[arm]['audit'] for arm in ('baseline','compact'))
assert a['queries']==b['queries']==1239
assert a['rows_including_group_exclusion']==b['rows_including_group_exclusion']==70844
assert a['raw_bits_sha256']==b['raw_bits_sha256'] and a['checksum']==b['checksum']
report=dict(host_only=True,physical_device_tested=False,baseline_commit=sys.argv[2],
 runtime_sha256=hashlib.sha256(pathlib.Path(sys.argv[3]).read_bytes()).hexdigest(),
 java_version=subprocess.run(['java','-version'],capture_output=True,text=True,check=True).stderr.strip(),
 platform=platform.platform(),arms=arms,raw_numeric_and_reference_parity=True,
 timing_assertions=False,whole_model_heap_measured=False,peak_heap_measured=False,
 scope='Reachable word/character indexes and exemplar metadata only; excludes base retriever, documents, head, app and temporary peak allocations.')
(root/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print('Runtime audit: 1,239 queries /70,844 rows match raw bits and original references; host timings are descriptive only')
PY
