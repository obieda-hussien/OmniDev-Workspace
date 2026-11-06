# Team Completion Protocol (P5)

P5 composes the existing P0 Finish Protocol across the existing Team execution DAG. It does not add a second agent finish gate, change tool backends, introduce another database, or change ordinary chat.

## Ownership and exact evidence

Each logical Team run has a runtime-created UUID. Every revision, worker and physical attempt has a distinct `RunIdentity`. The coordinator owns `WorkerAssignment` objects; stale, foreign or replaced assignments cannot publish accepted results. A serial promotion gets a fresh attempt identity even when its recovery counter restarts.

The agent emits a bounded `EvidenceRef` with each tool result after recording that exact result in `RunEvidenceLedger`. These stamps contain existing P0 identity/sequence/kind/outcome metadata. They are created by runtime code; model JSON cannot supply them. Tool execution events also carry the stabilized call ID.

`WorkerCompletionObserver` matches the complete P0 report against the exact observed stream, including call IDs, targets, sequence, outcomes, kinds, run and worker identities. Equal per-tool success counts are insufficient. Missing reports, missing or duplicate observations, foreign attempts and changed evidence are rejected. It then delegates acceptance criteria, action requirements, read-back and unresolved failures to the existing `WorkerEvidenceReview` / `FinishVerifier` rules.

Receipts bind the accepted worker output with a SHA-256 digest. The coordinator checks this binding before dependency handoff. It never retroactively attaches observations from another worker or retry. Reports and diagnostics contain operational task IDs, statuses, counters and digests rather than raw arguments, target paths, user outputs or private reasoning. Existing redacted, bounded tool observations remain available as worker context.

A self-contained explanation can use `toolFree=true`; it still requires a verified P0 report. Workers explicitly retain P0 when no tools are available. A planner cannot label a new execution objective as reasoning-only to bypass real execution; an initial implementation objective requires a verified implementation worker. Serialized research is not forced to mutate anything. Runtime promotion from a read wave to serial mutation retains the action obligation.

## Scheduling, recovery and completion

Only accepted receipts unlock dependencies. Graph references and task contracts remain validated; model-provided completed task statuses are rejected. Duplicate planner tasks retain the union of their acceptance criteria and cannot remove an evidence requirement during normalization.

Retries preserve the shared token limits and existing read/serial execution waves. A worker with a started, successful or uncertain side effect cannot be restarted blindly after failure. Exact policy denials that performed no action still allow the existing safe serial promotion. Runtime approval/intervention metadata stops retries, including when a terminal P0 report could not be emitted.

The coordinator builds `TeamCompletionReport` from all current task outcomes. Its status distinguishes completed, incomplete, failed, blocked and awaiting approval. Missing workers and failed/skipped prerequisites prevent completion. Original initial-run implementation obligations cannot disappear through a reasoning-only plan. On a user redirect, a new revision is evaluated against its new task contracts; old observations are context only, never automatically accepted receipts.

Synthesis is presentation, not authority. It runs only when the whole current revision has verified receipts; partial outcomes use a deterministic summary of failures, skips and bounded worker evidence. A model saying everything succeeded cannot turn an incomplete Team into a completed one.

## Existing UI, IPC and storage

`SwarmEvent.Completed` means the flow ended; its optional runtime completion report states whether the objective completed. Main chat stores the bounded verification summary and digest in the existing console JSON with the answer. An incomplete report adds an error entry, preventing a misleading DONE console header. Reloading the chat preserves that operational result without a Room migration. IPC likewise returns an error/Interrupted outcome for an unverified Team rather than a successful completion snapshot. Messaging integrations receive the same honest final summary.

The report and worker-attempt ownership are currently run-local. This phase does not implement durable coordinator restart, cross-process receipt restoration or Team-level P2 Resume. Ordinary P2 agent recovery is unchanged. The generic background chat restarter now refuses interrupted Team execution: a prose checkpoint cannot establish ownership or reconcile uncertain side effects. Users must review the saved observations before starting a new Team run. This prevents automatic replay rather than inventing recovered acceptance evidence.

Room remains version 19. P1/P2 agent task storage, P3 step planning and P4 tool surfaces keep their existing contracts. No SDKs, permissions, provider abstraction, model temperature or global tool-choice behavior changes.

## Validation and limits

Deterministic domain tests cover exact and forged receipts, stale attempts/revisions, dependencies, action requirements, safe denial versus uncertain effects, approval stops, output binding, merged acceptance contracts, privacy and 100 seeded DAGs. Scripted real Team/AgentPipeline tests cover dependency execution, failure containment, no blind side-effect retries, explanation without a backend and dishonest tool-free planning. Compose tests round-trip the existing console JSON and verify success versus awaiting-approval presentation. Existing steering and recovery tests remain part of the full flavor CI run.

The coordinator verifies execution contracts and evidence under P0; it does not provide a general semantic fact-checker for arbitrary prose. Observations are capped at 200 per attempt, matching P0 report bounds; an overlong or incomplete stream fails closed. Live provider behavior, interrupted-device scenarios and low-memory execution still require physical-device validation.
