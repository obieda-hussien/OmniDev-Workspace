# Harness engineering status

Audit baseline on 2026-11-25 was [`71037c9`](https://github.com/obieda-hussien/OmniDev-Workspace/commit/71037c959e20cb1a7979b0e7b902bed149ac4537).

| Phase | Evidence | Open work |
| --- | --- | --- |
| H0 Execution integrity | [#324](https://github.com/obieda-hussien/OmniDev-Workspace/pull/324) merged. The [Android workflow](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45370669076) passed the API 30 UI job and all-flavor test job. [#326](https://github.com/obieda-hussien/OmniDev-Workspace/pull/326) covers typed Termux file and process reads. [#327](https://github.com/obieda-hussien/OmniDev-Workspace/pull/327) covers browser navigation evidence independently. | Verify the follow-up with Android CI and Termux on the Android 11 reference device. Audit generic pipelines, timeout and cancellation outcomes, and mutation receipts independently. |
| H1 Capability registry | [#325](https://github.com/obieda-hussien/OmniDev-Workspace/pull/325) merged. It enumerates permitted tool names in pages without loading schemas. [#328](https://github.com/obieda-hussien/OmniDev-Workspace/pull/328) adds distinct registry counts and domain filters, with 12 catalog tests passing in an isolated JVM run. | Report registration, run authorization, backend availability, and loaded schemas separately. Use the existing tool registry and backend state. Add source categories and filters. |
| H2–H12 | Existing Agent, Team, Room, GitHub, attachment and scheduling components provide starting points. | Audit each runtime path and its incident replay coverage before marking a phase complete. |

## H0 replay results

The Linux baseline ran as an unprivileged user with a temporary directory whose read permission was removed. The old `ls | head` returned wrapper status 0 while `ls` returned 2. The pipeline from #324 returned 2 and reported the permission error.

The follow-up uses the existing `TermuxRunCommandBridge` Bash route. It drains stdout and stderr independently, returns one lookahead line per stream to detect truncation, and preserves stage failures during filtered reads. A diagnostic-free grep exit 1 is reported as `NO_MATCHES` with exit code 1. The advanced flavor adapter preserves transport and process failures when data is partial. The Termux plugin reports transport success as Android `Activity.RESULT_OK` and shell exit status separately. Transport-successful command failures carry the process code alone. Recursive reads still scan to completion or the existing Termux request deadline.

| Local test | Result |
| --- | --- |
| Kotlin 2.4.21 and JUnit 4.13.2, Linux Bash 5.2.37 | 10 passed. Covers failed and permission-denied listings, 100,000 stdout lines, 20,000 stderr lines, empty grep, grep error, failed upstream and limiter, partial results and exact line boundaries. |
| Isolated JVM adapter and outcome semantics | 17 more tests passed using production result conversion and normalization with the Android result constant supplied by a test stub. |
| Android Gradle | Local Gradle distribution download blocked. Advanced adapter tests and all-flavor verification run in Android CI. |
| Physical Termux / Infinix X689 | Pending. |

Continue H1 against the existing registry and coordinate source changes with the separate P11–P15 maintenance automation. No workload-level completion, latency, memory, or device baseline has been measured yet.
