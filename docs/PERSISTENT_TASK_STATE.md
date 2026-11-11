# Persistent task state

Agent runs bound by the [finish protocol](FINISH_PROTOCOL.md) are stored in the existing Room database
(`OmniDevDatabase`, version 20; P1 tables introduced in version 18) so that a run can be inspected and resumed from what really happened, not
from reconstructed chat history. Plain chat never touches these tables.

## Four layers that are never interchangeable

| Layer | Stored as | Meaning |
| --- | --- | --- |
| Plan | `agent_task_plans`, `agent_task_steps` | What the runtime intends to do. Revisions are kept; nothing is rewritten silently. |
| Execution | `agent_task_cycles`, `agent_task_invocations` | What was attempted: one cycle per model turn, one invocation per requested tool call. |
| Result | result columns of an invocation | What the tool returned: bounded, redacted, hashed. Not proof that the real-world goal was reached. |
| Verification | task finish record, step completion proof | What the P0 finish gate accepted. Only this can complete a task or a step. |

Hidden model reasoning is never stored. Cycles keep a concise operational summary only.

## Tables (migration 17 to 18, additive)

`agent_tasks` (original request written once and never updated, separate normalized objective, status, current cycle and
plan revision, heartbeat, finish record), `agent_task_plans`, `agent_task_steps`, `agent_task_cycles`,
`agent_task_invocations`, `agent_task_events` (every transition, unique per `(taskId, eventKey)` so a replayed event is a
no-op). Existing tables are untouched; `ToolExecutionJournal` stays an analytics log and is not used for recovery
(it is written after execution, keeps 10,000 rows and truncates results to 500 characters).

All rules live once in `TaskStateEngine`; Room and an in-memory backend only provide CRUD and `atomic { }`.
Each store operation is one short transaction; none is held across a model or network call.

## Lifecycle

- Task: `CREATED, RUNNING, INTERRUPTED, AWAITING_APPROVAL, BLOCKED, INCOMPLETE, COMPLETED, FAILED, CANCELLED`.
- Step: `PENDING, READY, RUNNING, COMPLETED, FAILED, BLOCKED, SKIPPED, INTERRUPTED`. `COMPLETED` is rejected unless it cites
  invocations of the same task that `SUCCEEDED`, or carries a written non-tool justification.
- Invocation: `PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED, UNKNOWN`. Requested, then marked `RUNNING`
  before it executes (write-ahead), then settled by its result. Calls blocked before dispatch end `CANCELLED`, never executed.

## Recovery

`TaskRecoveryPlanner` is a pure policy; `TaskRecovery` applies it with guarded, idempotent transitions, so recovery can
itself be interrupted and re-run. A fresh process settles tasks left running by the previous one (`TaskRecoveryBootstrap`). The sweep uses repeatable pages and rechecks each candidate's status and heartbeat in the same transaction that applies recovery, so an active task cannot be interrupted by an older candidate captured earlier.

| Found | Decision | Action |
| --- | --- | --- |
| Completed task | `ALREADY_COMPLETED` | none |
| Failed or cancelled task | `PERMANENTLY_FAILED` | none |
| Awaiting approval, blocked | `REQUIRES_USER_INTERVENTION` | none |
| Requested, never started | `SAFE_TO_RESUME` | invocation cancelled |
| Read-only call interrupted | `SAFE_TO_RETRY` | invocation interrupted |
| Side-effecting call interrupted | `REQUIRES_RECONCILIATION` | invocation `UNKNOWN` (never failed, never succeeded) |

A resumed run repeats nothing blindly: an identical side effect is refused while an earlier one is unresolved. A later read is diagnostic context, not reconciliation evidence by itself. `TaskRecovery.reconcile(taskId, invocationId, outcome, verification)` checks that the exact invocation belongs to the selected task, is a side effect in UNKNOWN or INTERRUPTED state, and has a nonblank verification statement. `CANCELLED` means the original effect is confirmed not to have executed; it restores to skipped evidence, not failed evidence. If the start of a side-effecting call cannot be stored, the call is not executed.

## Connection to the finish protocol

The recorder is reached only through `FinishRuntime` and two existing seams of `executeToolBatch`; there is no second
gate. A verdict is stored from the gate's own decision. A resumed run reuses the task id as `runId` and restores its
ledger from storage: `SUCCEEDED` invocations return as `OK`; failed, unknown and interrupted side effects return as `ERROR`
so they block completion, and skipped ones return as `SKIPPED`. The gate re-verifies; a verdict lost in a crash is never trusted.

## User-controlled continuation (P2)

The chat session queries only its own persistent task rows. The recovery card shows the original request, current objective,
latest plan revision, persisted step counts, latest cycle and bounded invocation counts. It does not load full successful
tool results into the conversation list. Dismissing a card hides the notice only; the task and audit history remain.

Resume is an explicit user action. The store normalizes pending and interrupted invocation/step records, applies the recovery
policy, and changes the task to RUNNING in one short transaction. A second caller sees a conflict. The resumed run keeps the
original task and session ids and enters the normal AgentPipeline/FinishRuntime path with the existing invocation evidence
restored into the P0 ledger. A new cycle is created; historical tool calls are never replayed. If resume setup fails before
the new cycle is stored, the unused claim is released.

The effective finish-gate enforcement flag, execution requirement and task kind are written to the existing
`constraintsJson` field for P2-created tasks. No Room migration is required. Older P1 rows have no such policy payload:
finish-protocol enforcement is preserved, but exact original `requireExecution` and `TaskKind` cannot be reconstructed for
every pre-P2 task and may be inferred from the original request on resume.

The chat card disables Resume while a side-effect result is unknown. A user can record SUCCEEDED, FAILED, or CANCELLED only
with a written verification statement tied to that exact invocation. P2 adds no arbitrary probes or automatic mutation
attempts. BLOCKED and AWAITING_APPROVAL tasks remain in a user-intervention state; P2 does not invent a parallel approval
protocol.

## Known limits

- Nothing resumes by itself. The user must press Resume in the original chat session; there is no background restart.
- The plan is the coordinator's acceptance list or one objective step per revision (new revision on a user redirect).
  The model does not author plans, and steps are attributed to the first open step rather than mapped per call.
- The verdict is stored right after the gate decides, concurrently with sending the answer. A crash in that window leaves
  the task `INTERRUPTED` with all execution records.
- Results are bounded (8,000 characters, head and tail kept, hash of the full redacted text); the full text is not kept.
- Automatic reconciliation probes per tool do not exist; `UNKNOWN` requires justified manual reconciliation. A generic later read does not unblock it.
- Worker and parent ids are stored but no team-level completion protocol is implemented here.


## Side-effect reconciliation safety

A later read is diagnostic context, not reconciliation evidence by itself. An unresolved side effect remains blocked
until its exact invocation ID is explicitly reconciled with a verification statement stored on that invocation.
Reconciliation verification is restored into the P0 ledger; a confirmed non-execution is recorded as CANCELLED
(not FAILED), so a later permitted retry is not permanently poisoned by a false failure. An identical side effect
already recorded as successful is also blocked from accidental duplicate execution.
Startup recovery is a readiness barrier: new agent task persistence waits for the previous-process sweep to finish,
and a failed sweep remains visible and prevents mutation execution.

## Structured planning (P3)

See [structured planning](STRUCTURED_PLANNING.md) for validated JSON plans, dependency scheduling, cycle-owned
step attribution, typed output checks, adaptive revisions and recovery. Version 19 adds only plan metadata;
the six P1 tables and shared Room/memory engine remain authoritative. P0 remains the final task completion gate.

Team-level coordinator recovery is documented separately in [P6 durable runtime](DURABLE_TEAM_RUNTIME.md). Single-agent P2 identities and tables retain their existing contracts.
