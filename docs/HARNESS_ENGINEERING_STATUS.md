# Harness engineering status

Baseline after verified merges on 2026-11-26 is [`a88fda7`](https://github.com/obieda-hussien/OmniDev-Workspace/commit/a88fda7c7229c69198a8712b919f1c1fd05459da).

## Telegram Business auto-replies

Enable Business Mode for the bot in BotFather, then connect it from Telegram's
automated chats settings with reply rights for the selected chats. Pair the
owner's private bot chat with OmniDev and send `/business_on <reply text>`.
`/business_off` disables replies locally. Keep the OmniDev bot listener running.

The listener verifies the active Business connection and paired owner before
using Telegram's business connection ID to reply. It uses owner-supplied text,
skips outgoing and bot messages, and limits repeat replies to one per peer every
five minutes while the listener is running. It persists the update offset before
dispatch. Incoming Business messages stay outside model context and chat history.
[#357](https://github.com/obieda-hussien/OmniDev-Workspace/pull/357) merged after [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45813727945) and the security gate passed. A linked-account smoke test remains pending.

## Telegram command and response integrity

Telegram now handles mode and session aliases locally, returns the actual mode,
checks build capabilities and workspace scope before switching, and routes
unknown bot commands to help. Chat status no longer presents the global tool
count as a per-session capability. Recognized device inventory, foreground
application and storage questions stop before text-only inference in Chat.
Replies strip reasoning blocks and reject execution-report claims in the
tool-free Chat path. Agent responses require a final answer instead of
falling back to draft streaming output.

Five isolated Kotlin/JVM tests cover the reported commands, pairing parsing,
reasoning tags, fabricated execution reports, and Arabic device questions.
The merged Telegram change passed Android CI, including API 30, and the security gate. A linked Telegram device check is pending. Cross-channel memory
requires a verified identity link and explicit data-sharing policy.


| Phase | Evidence | Open work |
| --- | --- | --- |
| H0 Execution integrity | [#324](https://github.com/obieda-hussien/OmniDev-Workspace/pull/324) merged. The [Android workflow](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45370669076) passed the API 30 UI job and all-flavor test job. [#326](https://github.com/obieda-hussien/OmniDev-Workspace/pull/326) and [#327](https://github.com/obieda-hussien/OmniDev-Workspace/pull/327) merged after Android CI passed, including API 30 instrumentation. [#332](https://github.com/obieda-hussien/OmniDev-Workspace/pull/332) merged after Android CI and the provenance workflows passed. [#337](https://github.com/obieda-hussien/OmniDev-Workspace/pull/337) merged with cancellation-safe Termux transport health. | Verify Termux on the Android 11 reference device. Audit timeout and cancellation outcomes and mutation receipts. [#343](https://github.com/obieda-hussien/OmniDev-Workspace/pull/343) merged with the default privileged file search's exit status and output limit preserved. |
| H1 Capability registry | [#325](https://github.com/obieda-hussien/OmniDev-Workspace/pull/325) merged. It enumerates permitted tool names in pages without loading schemas. [#328](https://github.com/obieda-hussien/OmniDev-Workspace/pull/328) merged with distinct registry counts and domain filters. | [#333](https://github.com/obieda-hussien/OmniDev-Workspace/pull/333) merged with run-observed shell readiness. [#334](https://github.com/obieda-hussien/OmniDev-Workspace/pull/334) merged with local and MCP registration-source filters. Continue readiness evidence for other backends. |
| H2 Schema management | [#335](https://github.com/obieda-hussien/OmniDev-Workspace/pull/335) and [#336](https://github.com/obieda-hussien/OmniDev-Workspace/pull/336) merged after Android CI. Native schema constraints count toward estimated input and current-turn observation matches survive same-turn schema rebuilds. | Replay provider requests and compare measured context use. |
| H4 Adaptive recovery | [#329](https://github.com/obieda-hussien/OmniDev-Workspace/pull/329) merged with safeguards against uncertain privileged shell replays. [#340](https://github.com/obieda-hussien/OmniDev-Workspace/pull/340) merged with reconciliation for missing Termux results after dispatch. | Audit transport recovery for additional mutation backends and replay on the Android 11 device. |
| H5 Context and evidence | [#330](https://github.com/obieda-hussien/OmniDev-Workspace/pull/330) and [#331](https://github.com/obieda-hussien/OmniDev-Workspace/pull/331) merged. The latter keeps an unresolved failure until its recorded target succeeds. | [#341](https://github.com/obieda-hussien/OmniDev-Workspace/pull/341) merged with ledger retention. [#342](https://github.com/obieda-hussien/OmniDev-Workspace/pull/342) merged with OpenAI-compatible usage details through runtime accounting. Replay other provider formats and audit the analytics path. |
| H3 Tool-call repair | [#339](https://github.com/obieda-hussien/OmniDev-Workspace/pull/339) merged with canonical text-envelope guidance in preflight feedback. | Replay malformed native arguments and schema correction through provider-backed sessions. |
| H6 Environment routing | [#338](https://github.com/obieda-hussien/OmniDev-Workspace/pull/338) merged with stricter Team provider-status detection. | [#344](https://github.com/obieda-hussien/OmniDev-Workspace/pull/344) merged with scoped file dispatch after a backend outage. Replay on Android and verify worker routing. |
| H7 Objective continuity | [#345](https://github.com/obieda-hussien/OmniDev-Workspace/pull/345) merged with persisted workspace restoration during explicit task resume. | Replay the UI handoff and verify routing on the saved objective across provider prompts. |
| H8 Pagination | [#346](https://github.com/obieda-hussien/OmniDev-Workspace/pull/346) merged with same-revision coverage checks. [#347](https://github.com/obieda-hussien/OmniDev-Workspace/pull/347) merged with successful page receipt restoration after resume. | Replay the next-page handoff with a live provider and verify it on the reference device. |
| H9 Stall detection | [#348](https://github.com/obieda-hussien/OmniDev-Workspace/pull/348) merged with typed GitHub pagination scoring. [#349](https://github.com/obieda-hussien/OmniDev-Workspace/pull/349) merged with page novelty gating both recovery budgets. | Replay live pagination and audit the other duplicate-tool recovery paths. |
| H10 Team routing | [#350](https://github.com/obieda-hussien/OmniDev-Workspace/pull/350) merged with the handoff check before worker startup. [#351](https://github.com/obieda-hussien/OmniDev-Workspace/pull/351) merged with single-Agent routing for a change and its verification in Auto. | Replay automatic routing and the permission prompt on device. |
| H11 Attachments | [#352](https://github.com/obieda-hussien/OmniDev-Workspace/pull/352) merged with explicit failures for unavailable routine frames. [#353](https://github.com/obieda-hussien/OmniDev-Workspace/pull/353) merged with an early Local Edge rejection for image-bearing requests. | Verify live-provider analysis, physical-device sampling, and the other attachment paths. |
| H12 Autopilot | [#354](https://github.com/obieda-hussien/OmniDev-Workspace/pull/354) merged after [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45794213471) and the [security gate](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45794213562) passed. Interrupted and timed-out occurrences are preserved for review. | [#358](https://github.com/obieda-hussien/OmniDev-Workspace/pull/358) merged after [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45806497024) and the [security gate](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45806496860) passed. Verify service termination, process death and manual review on the reference device. |

## H11 routine video frame evidence

The merged result carries video-frame availability into the Agent evidence ledger. Previously, a non-vision model or an unusable frame received a warning while the tool result stayed successful. Missing and malformed payloads, frames above the existing encoded size limit, and frames unavailable to the selected model now become explicit failed observations. The Agent attaches a bounded JPEG sample to a vision-capable request and keeps the encoded image out of text. The frame receipt describes one sample awaiting inspection. JPEG markers provide a basic format check.

Five focused Kotlin 2.4.21 and JUnit 4.13.2 tests passed on Linux JVM. The fixtures cover a 159-byte JPEG, metadata-only output, malformed Base64, non-JPEG bytes, the existing 600,000-character limit, a non-vision model, and tool-error propagation. The failure paths are checked against the production run evidence ledger. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45772051243), including the API 30 UI job, and the [required security check](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45772051294) passed on the merged head. Physical-device sampling and live-provider analysis remain pending.

### H11 image delivery at the completion boundary

The Local Edge adapter serializes prompts as text. Requests containing image bytes now fail with an explicit vision-capability message before local inference in both normal and streaming execution. This also applies when the image is present in retained request history. A loopback HTTP fixture verified the exact JPEG data URL in the OpenAI-compatible request. It verifies delivery of bytes to the endpoint fixture. Live model analysis remains pending.

Both local rejection tests failed against the previous path. All 16 completion and token-accounting tests passed on Linux JVM with Kotlin 2.4.21 and JUnit 4.13.2. The project language and flavor source checks passed. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45778816291) and the [required security check](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45778816322) passed on the merged head. Physical-device replay remains pending.

## H12 scheduled run interruption

Process restart already paused persisted running tasks. Service cancellation within the same process could leave a task stuck as running, while a timeout could fail it and schedule an automatic retry. The service now records both as interrupted and pauses the claimed occurrence for review. The scheduler retains its recurrence anchor and retry count through the existing Room snapshot. An explicit cancellation takes precedence and an interruption from a mismatched start time cannot pause the current run. The existing P14 verification gate still determines scheduled success.

Twelve scheduler and P14 verification tests passed on Linux JVM with Kotlin 2.4.21 and JUnit 4.13.2, using the production scheduler and serialized entity with Room annotations stubbed. Fixtures cover a timeout with retries remaining, stale cleanup, explicit cancellation, recurrence, and restored state. Language and flavor source checks passed. Android service and Room integration verification remain pending. Next, replay an interrupted scheduled run on API 30 and audit retry eligibility for structured failures returned after dispatch.

### Scheduled retries after structured failures

The scheduler now retains a failed occurrence for review when the runtime
reports a known successful write or action, an unknown mutation outcome, or
an approval requirement. Existing retry rules still cover ordinary eligible
read failures. The decision uses typed runtime evidence, error classifications, and the execution policy for the dispatched call. A successful MCP operation can block replay even when its result is empty or the evidence kind is OTHER. Explicitly recognized read-only calls retain their retry eligibility.
The review flag defaults to false for older serialized summaries. Fifteen Linux JVM tests passed, including an empty successful MCP result and a GitHub read control. Android service and device
verification are pending.

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

Cancelling a Termux wait or reaching a caller deadline used to mark the transport unavailable and block later requests. The bridge now propagates caller cancellation, removes pending callbacks, and preserves transport health. The bridge's own response deadline and service start failure still produce transport failures. Commands already dispatched to Termux may finish after the caller stops waiting, so any uncertain effect still requires reconciliation before replay. Both cancellation tests reproduced `UNAVAILABLE` on the old wait logic. All 31 focused Kotlin/JVM tests passed after the fix, covering cancellation, tool outcome semantics, and execution boundaries. Physical device validation is pending.

### Privileged file search

The default `find_files` path used `find ... 2>/dev/null | head`, which could turn an inaccessible search into a successful empty listing. This path now uses the existing draining read pipeline with pipefail and an extra output line to detect the 200-line presentation limit. A failed process stays a failure and an empty successful search keeps its no-match outcome.

The missing-directory and truncation regressions failed before the fix. All 13 privileged and runtime read tests passed on Linux with Bash and three targeted tests also passed with Linux mksh R59, using the production command builder and a subprocess fixture. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45708698218) and [the required security check](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45708698228) passed at the merged head. A physical privileged-backend replay is pending. The detailed listing mode, the privileged transport's lower character cap, and other privileged pipelines require separate verification. The H6 replay below covers the independently authorized workspace read.

### Privileged file comparison

[#359](https://github.com/obieda-hussien/OmniDev-Workspace/pull/359) merged after [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45813721251) and the security gate passed. The previous `diff_files` wrapper returned success when `diff` failed behind the output limiter. The comparison now drains bounded output, treats exit 1 as a valid difference, and propagates actual errors. Identical inputs get an explicit result. Recursive comparison uses the tool's documented directory behavior.

Sixteen focused Linux JVM tests passed with Bash 5.2.37 and GNU diff 3.10. The six privileged comparison and find tests also passed with Linux mksh R59. The test suite verifies an error from a diff subprocess, identical inputs including a path starting with a dash, and line truncation of a recursive diff. Recheck the same paths with Android 11 Toybox and the privileged transport. The remaining H0 audit covers advanced search, diagnostics, and process result adapters.

## H1 backend evidence

Agent inventory pages can report Android shell, Termux and rish status from the current run's execution circuit and trusted probe metadata. The inventory does not start a probe, and unobserved routes and other backends remain unknown. A probe reporting unavailable can update the inventory without creating a dispatch block. Provider discovery, tool authorization and schema selection are independent of this status. A focused isolated JVM run passed 27 catalog and runtime-ledger tests, including no-evidence, independent recovery, and paginated availability cases.

Use `inventory:local` and `inventory:mcp` to filter permitted registrations by source. A returned cursor such as `inventory:mcp:40` keeps its filter on the next page. Sources come from the registration lists, independently of tool name prefixes. All 28 catalog and runtime-ledger tests passed in an isolated Kotlin 2.4.21 JVM run, including the source filter, cursor, direct reply and authorization regression.

## H2 schema refresh

The catalog drops observation-only entries in a same-turn rebuild if it only receives the original objective and the new discovery hint. It now retains up to two ranked catalog matches plus environment-priority names from the current observation. The next preparation replaces those hints. Existing source priority, schema budgets, and permission checks govern the refreshed surface. The regression failed on the baseline and passed alongside 28 other catalog and runtime-ledger tests in an isolated Kotlin 2.4.21 JVM run.

## H3 malformed text envelopes

The text adapter can represent a malformed envelope with an internal discovery proposal carrying a parse error. Preflight now gives those proposals the same complete envelope example advertised by the text protocol. Malformed calls still fail before dispatch, and corrected proposals go through the ordinary tool contract. Native discovery keeps its existing arguments guidance. The regression failed on the baseline and passed alongside 33 other adapter, preflight and correction tests using Kotlin 2.4.21 on an isolated JVM.

## H4 Termux mutation deadlines

A returned Termux response deadline after dispatch means the outcome of a side effect is unknown. Agent and Chat now pass their existing read-only decision to result normalization. The existing Agent recorder persists `MUTATION_OUTCOME_UNKNOWN` as `UNKNOWN`, and replay of an identical effect requires exact reconciliation. Verified read-only operations keep their timeout guidance. A regression using the orchestration entry point failed on the baseline because it kept the retryable timeout classification. All 25 result semantics and orchestration tests passed after the fix using Kotlin 2.4.21 on JVM. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45679424466) passed all five flavor unit test targets and the API 30 UI job. The [security gate](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45679424356) passed at the same PR head. Physical device replay is pending.

## H5 run ledger under context pressure

Request trimming could discard the runtime's run ledger through the fallback for old summaries, before trying to shorten the latest tool output. The ledger now stays intact through trimming. It keeps outcome evidence available alongside the objective and the latest tool-call/reply group. If the required context still exceeds the budget, the existing Agent request guard blocks the provider call.

Two regressions failed on `ad78e24` and passed after the change. A 10,000-character tool result fits a 500-estimated-token input budget after truncation with the run ledger preserved. A ledger that exceeds the budget by itself stays intact and over budget for the caller to detect. All 24 compaction and token-accounting tests passed with Kotlin 2.4.21, Java 21 and JUnit 4.13.2 on an isolated Linux JVM. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45689321646) and the [required security check](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45689321961) passed at the merged PR head. Physical device replay is pending.

## H5 OpenAI-compatible usage accounting

Normal Chat Completions responses and SSE usage events now carry optional cached-input and reasoning counts through `TokenUsage` and `TokenAccounting`. They record reported subdivisions of input and output. The reported total remains authoritative for execution budgets. Missing or malformed optional details stay unknown and an explicitly reported zero survives.

Three local loopback HTTP regressions failed before wiring the new fields and passed afterward. They replay a regular completion, a usage-only SSE event after the last choice, and an unexpected optional details shape. The fixture reports 180 input and 64 output tokens. Its 128 cached-input and 48 reasoning tokens remain within the 244-token total. All 13 completion and accounting tests passed on the isolated Linux JVM with Kotlin 2.4.21, Java 21 and JUnit 4.13.2. The [first Android CI run](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45699756054/job/139506444580) failed while compiling the Lite test fixture because the Android test classpath does not expose `jdk.httpserver`. The fixture now uses standard `java.net.ServerSocket` on loopback. All 13 completion and accounting tests passed again with the replacement. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45708203206) and [the required security check](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45708203216) passed at the repaired head. Physical-device validation is pending.

Next, confirm the remaining providers' usage contracts before extending this normalization. The new fields are available to runtime callers. Room analytics and the UI still show their existing totals. Coordinate with P11–P15 before editing shared components. Workload-level completion, latency, memory and device baselines remain pending.

## H6 independent workspace reads

`RuntimeBackendLedger` now attributes typed Termux file reads and privileged file searches to the transport that actually dispatches them. A typed read blocked by an observed outage stays blocked in its own domain, while other authorized tools remain available. Path-specific permission errors do not mark the service unavailable. The existing stagnation guard still handles repeated blocked calls.

The advanced-flavor replay passes a simulated unavailable-Android-shell result through production normalization and batch dispatch. It verifies one privileged dispatch, a rejected retry, and a real file read through the unprivileged workspace manager inside the selected workspace. Canonical scope matching now requires a directory boundary and rejects a neighboring folder with the same name prefix. The 31 catalog and ledger tests and the scoped-file replay passed on Linux JVM. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45717520547) and the [required security check](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45717520570) passed at the merged head, including the API 30 UI job. Physical replay with Shizuku stopped is pending.

## H7 workspace continuity

Agent task creation records the effective workspace in the existing task constraints JSON. The explicit resume uses that stored workspace before consulting chat history or the current Target Context. Tasks created before this metadata retain their existing fallback. A root workspace requires the current God Mode setting, and tool permissions and mutation reconciliation still apply at execution time.

The recovery replay starts a task through the finish runtime, interrupts it, and resumes it with a later message that repeats the original request in a different workspace. It verifies that the saved workspace, including spaces and quotes, survives the ambiguity, an empty history, and an acceptance update. The replay also covers explicit empty scope, legacy fallback, and the current God Mode check. All 48 persistence and recovery tests passed on Linux JVM with Kotlin 2.4.21 and JUnit 4.13.2. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45724892849) and the [required security check](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45724892901) passed at the merged head. Physical device validation is pending.

## H8 GitHub file-page continuity

Each `read_file` page reports the SHA-256 of the decoded file text. A continuation can pass the same digest as `file_sha256` and receives an explicit error if the content changed. The application still honors the requested ref. The finish gate for detailed README requests verifies contiguous coverage through EOF within one digest, so pages from changed files cannot be combined. Out-of-range cursors and oversized lines cannot supply EOF evidence.

All 45 Linux JVM tests passed. The fixtures cover matching and changed bodies, skipped and duplicated pages, terminal newlines, and out-of-range reads. The mixed-revision replay fails when digest grouping is removed. The GitHub tool source pin and the manifest that references it were refreshed after checking the definition anchor and the unchanged corpus. All 11 provenance checks passed. The authored corpus and checkpoint results are preserved. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45732752715), the [security gate](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45732753541), [Neural Core CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45732752907), and the [topic rerank replay](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45732752909) passed at the merged head. Live provider and physical device replays are pending.

## H8 page receipts across task recovery

The in-process evidence ledger and saved-task recovery now use the same page receipt parser. Recovery reconstructs the line range and file digest from summaries of successful read-only invocations. The existing summary retains the start and end of long observations, so the header and continuation marker remain available without a schema change. Failed reads cannot restore page coverage. When a detailed README remains incomplete, the finish gate returns the next line and the digest for that continuation.

The regression records a page large enough to trigger the 8,000-character summary limit, interrupts the task, and restores its evidence. It rejects a failed page and a page from changed content, then verifies completion when the next page matches the original digest. The replay failed before restoring page receipts. All 70 recovery and finish-protocol tests passed on Linux JVM using Kotlin 2.4.21 and JUnit 4.13.2. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45739110214) and the [security gate](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45739110113) passed at the merged head. Live continuation and physical replay are pending.

## H9 progress during GitHub pagination

The generic stagnation signature normalizes line numbers and hashes. For typed GitHub file reads, the detector now compares the repository endpoint, requested start line, and the existing successful page receipt. Changing only the requested page size cannot reset repetition if the returned coverage stays the same. GitHub calls use the existing read-only policy for the read/action signal.

Both regressions failed before the change. With the Agent's 0.76 threshold, eight advancing page fixtures remained novel and six observations of the same partial page triggered strategy stagnation. All 78 stagnation, recovery, and finish-protocol tests passed on Linux JVM using Kotlin 2.4.21 and JUnit 4.13.2. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45745125373) and the [required security check](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45745125418) passed at the merged head. Live-provider replay and physical-device measurements are pending.

### Recovery after repeated page reads

The Agent previously reset both recovery budgets on every successful GitHub file read. It now uses the detector's existing bounded receipt history to reset both budgets only on a new page observation. Sending stagnation guidance clears the scoring window and keeps the receipt history. A regression exhausts both budgets, replays a page after clearing that window, then verifies both budgets reopen when the next page arrives. All 92 focused tests passed on Linux JVM using Kotlin 2.4.21, Java 21 and JUnit 4.13.2. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45751532136) and the [required security check](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45751532190) passed at the merged head. Physical-device replay is pending.

## H10 Team handoff before worker dispatch

Fresh foreground Team runs now consult the existing single-Agent handoff policy against the validated plan inside the coordinator, before dispatching workers. The previous UI collector handled a buffered event, so worker startup could race with the permission check. Durable Team resumes continue on their saved plan.

The regression suspends the handoff decision for a one-task write plan and verifies one planner request, zero worker requests and zero tool calls when a handoff is offered. It passed against the production coordinator on a Linux JVM with Kotlin 2.4.21, Java 21, JUnit 4.13.2 and Android-specific dependencies stubbed. Launching the handoff check asynchronously reproduced the failure. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45757842412), including the API 30 UI job, and the [required security check](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45757842417) passed on the merged PR head. Physical-device replay is pending.

### Auto routing for verification of the same change

The classifier treated the security, database, UI and test terms in a request to fix one bug and verify that fix as independent work. The existing Auto route now classifies clear edit-and-verify requests as Agent before planner selection. The same independence check feeds learned mode selection. Explicit independent work remains eligible for Team, and manual mode selection still follows the existing dispatch path.

The dependent-work regression failed on the previous classifier. All 13 classifier tests passed on Linux JVM using Kotlin 2.4.21 and JUnit 4.13.2. The fixtures cover English and Egyptian Arabic dependent verification, a parallel request with final verification, and a hypothetical question. [Run Quality for #351](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45764783480/job/139722962661) failed on the multilingual input fingerprint. The reviewed diff preserves the existing parallel terms and adds input-only verification expressions, so the classifier's fingerprint was updated. The project language check and flavor source check passed locally. [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45771765686) and the [required security check](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45771765614) passed on the repaired, merged head. Physical-device verification and performance measurements remain pending.

### H0 privileged disk usage

The old `disk_usage` command returned status 0 for a missing path because the
output limiters masked the failed `df` and `du` stages. The report now stops if
the filesystem summary fails, preserves a failed traversal's diagnostic and
partial output, and flags size reports with more than 60 entries. It uses the
existing privileged read path and command deadline.

Sixteen focused Kotlin/JVM tests passed with Linux Bash 5.2.37 and GNU
coreutils 9.7. Six privileged file tests also passed with Linux mksh R59.
Fixtures cover a missing path, a failed traversal after partial output, and
a long report. [#360](https://github.com/obieda-hussien/OmniDev-Workspace/pull/360) merged after [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45813981346) and the security gate passed. The Android 11 device replay remains pending.
Next, compare diagnostic and exit-code reporting over Shizuku and rish.

### H0 privileged hex dump

The `dd | cat -v` fallback used the formatter's exit status and suppressed read
diagnostics. A partial read could therefore appear successful. The fallback now
propagates both stage failures and all dump paths retain diagnostics.

Twenty-two focused Kotlin/JVM tests passed on Linux Bash 5.2.37, including
three new regressions for failed fallback reads, formatter diagnostics, and
successful bounded reads. The three new tests also passed on Linux mksh R59.
[#361](https://github.com/obieda-hussien/OmniDev-Workspace/pull/361) merged after [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45824442639) and the [security gate](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45824442557) passed. Replay through Shizuku and rish on the reference device remains pending.

### H0 privileged filesystem output capture

The execution router already bounded successful Shizuku and rish output at
8,000 characters. The filesystem tool received a string and compared it to
its own 12,000-character limit, so a clipped response looked complete. The
router now retains its truncation flag. The rish path also carries the
existing Termux transport flag forward. The filesystem tool reports the flag
and shows a truncation notice. Legacy string callers keep their existing
output, and Lite still rejects privileged execution.

Twenty-five focused Kotlin/JVM tests passed on Linux with Android dispatch
stubbed. Three new cases cover 8,000 characters exactly, 8,001 characters
clipped by the router, and earlier transport truncation. [#362](https://github.com/obieda-hussien/OmniDev-Workspace/pull/362) merged after [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45834024279) and the [security gate](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45834024337) passed. Physical replay remains pending.

### H0 Shizuku process failures

Completed Shizuku processes with a nonzero exit now carry their exit code and
bounded partial output into the filesystem tool result. The typed result
prevents diagnostic text from triggering a transport retry or backend
fallback for a process that already returned. Shizuku transport exceptions
still follow the existing retry and replay policy. The filesystem adapter
keeps failed executions marked as errors, including when output is truncated.

Twenty-eight Linux Kotlin/JVM tests passed with Android dispatch stubbed.
New cases exercise exit 13 with partial output, a truncated failed response,
and a transport error with no process exit code. [#363](https://github.com/obieda-hussien/OmniDev-Workspace/pull/363) merged after [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45843711417) and the [security gate](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45843711392) passed. Android 11 device replay is pending. Next, distinguish completed rish
commands from launcher or transport failures before forwarding their exit
codes through the same boundary.

### H0 process failures during tool-result normalization

The normalizer inferred retry and backend-blocking flags from the body of a
failed result even when a completed process had already reported a nonzero
exit. Historical diagnostic text in partial output could then trigger
transport recovery or mark the backend unavailable. For typed nonzero exits,
the normalizer now preserves the producer's recovery flags. Untyped
transport failures retain the existing diagnostic classification.

Two regressions reproduced the false retry and false permission block before
the fix. All 27 focused Linux Kotlin/JVM semantics and orchestration tests
passed after it. [#364](https://github.com/obieda-hussien/OmniDev-Workspace/pull/364) merged after [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45853260382) and the [security gate](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45853260140) passed. Device replay is pending.

### H0 Shizuku outcomes in the Android terminal router

The automatic Android route used only the failure reason from Shizuku even
when the backend supplied a process exit code, partial output and truncation.
The terminal now forwards those fields as a completed process failure.
A service timeout retains its transport classification and has no process
exit code. The change applies after dispatch and does not change authorization
or backend selection.

Eighteen focused Linux Kotlin/JVM tests passed with Android transport stubbed.
Fixtures cover exit 13 with partial output, exit 2 with historical timeout
text, and a service timeout with no process code. [#365](https://github.com/obieda-hussien/OmniDev-Workspace/pull/365) merged after [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45862676781) and the [security gate](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45862676856) passed. Device replay remains pending.

### H0 rish filesystem command completion

The filesystem capture path now asks the delegated shell to append a unique
completion line to stdout. It accepts a process exit only with a successful
Termux transport callback and a matching completion line. A nonzero outer
status that conflicts with the inner status is inconclusive. Returned output
omits the completion line. Confirmed process failures keep their partial
output and rish identity, and do not enter backend fallback. A missing line
cannot prove success, including if transport truncation removes it.

The 31 focused Kotlin/JVM tests include real Linux shell cases for exit 13,
zero exit, quoted partial output, and unsuccessful or inconsistent callback
fixtures. Android transport was stubbed. [#366](https://github.com/obieda-hussien/OmniDev-Workspace/pull/366) merged after [Android CI](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45871288966) and the [security gate](https://github.com/obieda-hussien/OmniDev-Workspace/actions/runs/45871289049) passed. Verify the receipt and truncation behavior on Android 11 before extending it to explicit terminal rish calls.

### H0 contradictory process status

A result marked successful could keep that status despite a typed nonzero
exit. The normalizer now marks that combination as failed, drops success
verification and regenerates the status header. A typed grep exit 1 with
`NO_MATCHES` keeps its empty-search outcome across repeated normalization.

Both regressions failed on the previous code. All 29 focused Linux
Kotlin/JVM semantics and orchestration tests passed after the fix. Android
CI, physical rish verification and device measurements are pending.
