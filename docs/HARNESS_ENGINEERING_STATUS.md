# Harness engineering status

Baseline after verified merges on 2026-11-25: [`6a02a68`](https://github.com/obieda-hussien/OmniDev-Workspace/commit/6a02a6878e0f0395cebb5450a2dc0c3cd5dd352e).

| Phase | Evidence | Open work |
| --- | --- | --- |
| H0 Execution integrity | [#324](https://github.com/obieda-hussien/OmniDev-Workspace/pull/324) merged. The [Android workflow](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45370669076) passed the API 30 UI job and all-flavor test job. [#326](https://github.com/obieda-hussien/OmniDev-Workspace/pull/326) and [#327](https://github.com/obieda-hussien/OmniDev-Workspace/pull/327) merged after Android CI passed, including API 30 instrumentation. [#332](https://github.com/obieda-hussien/OmniDev-Workspace/pull/332) merged after Android CI and the provenance workflows passed. | Verify Termux on the Android 11 reference device. Audit timeout and cancellation outcomes and mutation receipts. |
| H1 Capability registry | [#325](https://github.com/obieda-hussien/OmniDev-Workspace/pull/325) merged. It enumerates permitted tool names in pages without loading schemas. [#328](https://github.com/obieda-hussien/OmniDev-Workspace/pull/328) merged with distinct registry counts and domain filters. | [#333](https://github.com/obieda-hussien/OmniDev-Workspace/pull/333) merged with run-observed shell readiness. This follow-up adds local and MCP registration-source filters. Continue readiness evidence for other backends. |
| H4 Adaptive recovery | [#329](https://github.com/obieda-hussien/OmniDev-Workspace/pull/329) merged with safeguards against uncertain privileged shell replays. | Audit transport recovery and reconciliation for other mutation types. |
| H5 Context and evidence | [#330](https://github.com/obieda-hussien/OmniDev-Workspace/pull/330) and [#331](https://github.com/obieda-hussien/OmniDev-Workspace/pull/331) merged. The latter keeps an unresolved failure until its recorded target succeeds. | Replay cross-provider compaction and recovery scenarios. |
| H2–H3, H6–H12 | Existing Agent, Team, Room, GitHub, attachment and scheduling components provide starting points. | Audit each runtime path and its incident replay coverage before marking a phase complete. |

## H0 replay results

The Linux baseline ran as an unprivileged user with a temporary directory whose read permission was removed. The old `ls | head` returned wrapper status 0 while `ls` returned 2. The pipeline from #324 returned 2 and reported the permission error.

The follow-up uses the existing `TermuxRunCommandBridge` Bash route. It drains stdout and stderr independently, returns one lookahead line per stream to detect truncation, and preserves stage failures during filtered reads. A diagnostic-free grep exit 1 is reported as `NO_MATCHES` with exit code 1. The advanced flavor adapter preserves transport and process failures when data is partial. The Termux plugin reports transport success as Android `Activity.RESULT_OK` and shell exit status separately. Transport-successful command failures carry the process code alone. Recursive reads still scan to completion or the existing Termux request deadline.

| Local test | Result |
| --- | --- |
| Kotlin 2.4.21 and JUnit 4.13.2, Linux Bash 5.2.37 | 10 passed. Covers failed and permission-denied listings, 100,000 stdout lines, 20,000 stderr lines, empty grep, grep error, failed upstream and limiter, partial results and exact line boundaries. |
| Isolated JVM adapter and outcome semantics | 17 more tests passed using production result conversion and normalization with the Android result constant supplied by a test stub. |
| Android Gradle | Local Gradle distribution download blocked. Advanced adapter tests and all-flavor verification run in Android CI. |
| Physical Termux / Infinix X689 | Pending. |

The generic `pipe_exec` baseline returned exit 0 when an upstream stage returned 17 and `cat` succeeded. The follow-up validates JSON stages before dispatch and uses the existing Termux Bash route with `pipefail`. All 14 focused JVM tests passed using Kotlin 2.4.21 and Linux Bash, including the four generic pipeline regressions. The contract and prototype source pins also now include the reviewed documentation change from #329.

## H1 backend evidence

Agent inventory pages can report Android shell, Termux and rish status from the current run's execution circuit and trusted probe metadata. The inventory does not start a probe, and unobserved routes and other backends remain unknown. A probe reporting unavailable can update the inventory without creating a dispatch block. Provider discovery, tool authorization and schema selection are independent of this status. A focused isolated JVM run passed 27 catalog and runtime-ledger tests, including no-evidence, independent recovery, and paginated availability cases.

Use `inventory:local` and `inventory:mcp` to filter permitted registrations by source. A returned cursor such as `inventory:mcp:40` keeps its filter on the next page. Sources come from the registration lists, independently of tool name prefixes. All 28 catalog and runtime-ledger tests passed in an isolated Kotlin 2.4.21 JVM run, including the source filter, cursor, direct reply and authorization regression.

Continue H2 schema management against the existing registry and coordinate source changes with P11–P15. No workload-level completion, latency, memory, or device baseline has been measured yet.
