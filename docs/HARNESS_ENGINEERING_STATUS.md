# Harness engineering status

Baseline after verified merges on 2026-11-25: [`e2d6e4f`](https://github.com/obieda-hussien/OmniDev-Workspace/commit/e2d6e4fabfcab456ab5810b32e95ea29ee901b10).

| Phase | Evidence | Open work |
| --- | --- | --- |
| H0 Execution integrity | [#324](https://github.com/obieda-hussien/OmniDev-Workspace/pull/324) merged. The [Android workflow](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45370669076) passed the API 30 UI job and all-flavor test job. [#326](https://github.com/obieda-hussien/OmniDev-Workspace/pull/326) and [#327](https://github.com/obieda-hussien/OmniDev-Workspace/pull/327) merged after Android CI passed, including API 30 instrumentation. This follow-up covers the generic Termux pipeline executor. | Verify Termux on the Android 11 reference device. Audit timeout and cancellation outcomes and mutation receipts. |
| H1 Capability registry | [#325](https://github.com/obieda-hussien/OmniDev-Workspace/pull/325) merged. It enumerates permitted tool names in pages without loading schemas. [#328](https://github.com/obieda-hussien/OmniDev-Workspace/pull/328) merged with distinct registry counts and domain filters. | Report backend availability from existing runtime evidence and add provider-source categories. |
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

Continue H1 against the existing registry and coordinate source changes with the separate P11–P15 maintenance automation. No workload-level completion, latency, memory, or device baseline has been measured yet.
