# Finish protocol

Agent runs bound by the protocol end through a runtime-controlled check instead of trusting the model's
words. This page describes what is enforced, what is **not** persisted, and how to measure it.

## When it applies

| Run | Bound by the protocol |
| --- | --- |
| Pure chat, or a run with no permitted tools | No. Normal replies are never intercepted. |
| Agent run with execution intent (`executionIntent >= 0.34` or `mutationIntent >= 0.16`) | Yes. |
| Team worker with tools | Yes. |

`finish_task(status, summary, evidence, criteria)` is exposed only in bound runs and is intercepted before tool
execution. A plain-text final answer in a bound run counts as an implicit `completed` claim and is checked the same way.

## Statuses

`completed`, `blocked`, `failed`, `awaiting_approval`, `incomplete`. Only `completed` is verified. The others are
honest terminal states: they are published with a runtime-added label, never marked verified, and a worker that ends
in one of them is not retried by the coordinator.

## What `completed` must satisfy

Evidence comes only from the run ledger, which the runtime fills from real tool results.

- R1 (when the task needs execution): at least one successful tool call exists.
- R2: no unresolved failed call (a later success of the same tool resolves it; empty results never block).
- R3: every successful file edit was read back afterwards (same path, or a pathless read).
- R4 (implementation workers): at least one successful non-read action, not only reads.
- A1..An: each acceptance item from the coordinator's task is claimed with valid evidence ids.
- Every cited evidence id must exist in this run, belong to this worker and have ended `OK`.

Without planner criteria the baseline checks still apply: a missing criteria list is never an automatic pass.
Claims about verification of results (for example "tests pass") are only as strong as the cited tool output.

## Rejections

A rejected claim returns `FINISH_REJECTED` feedback and the run continues. After the third rejection (four for a
Flash-Lite profile) the run ends as `[Incomplete]` with the reason. It is never published as success. The report
carries `rejections`, `malformed` and `implicit` counters for measurement.

## Team workers

Each worker gets a runtime-created `RunIdentity` (run id, worker id, task kind). A worker returns a `CompletionReport`;
the coordinator rejects it unless the status, run id, worker id, digest, ledger rules and acceptance items hold, and
unless every successful tool result in the report was also observed on the coordinator's own event stream.
A matching digest alone is not accepted as proof.

| Worker kind | Requirement |
| --- | --- |
| Read-only (`parallelSafe=true`) | At least one successful tool call; never forced to modify files. |
| Implementation (`parallelSafe=false`) | Successful non-read action plus read-back of edits. |
| `toolFree` synthesis with dependencies | No tool evidence required. |

## Lifecycle limits (read before relying on it)

The ledger and run state are **in memory and scoped to one `AgentPipeline.execute` call**.

| Event | Ledger / run state |
| --- | --- |
| New model request in the same run | Kept; re-rendered into every request. |
| Context compaction in the same run | Kept; it is not stored in the message history. |
| Live steering of the same run | Kept; the goal text is updated. |
| New `execute` call, pipeline recreation, retry attempt | Fresh ledger; earlier evidence cannot satisfy the new run. |
| App process death or restart | Lost. |
| Resuming an earlier conversation | Fresh ledger. Old tool results in the history are context, not evidence. |

This is deliberate: stale evidence must not complete a different run. If recovery after process death is
needed, the smallest design is an append-only run-evidence journal keyed by run id, written after each tool result and
read only to resume that same run. It is not implemented and needs its own migration review.

## Known limits

- The plan is the coordinator's acceptance list plus runtime baseline checks; the model does not author a plan.
- Retries of a worker that failed after non-idempotent side effects are bounded by the existing attempt limit and the
  recovery note, but are not blocked outright. Honest terminal states are never retried.
- Gate behaviour is covered by deterministic tests. Model-level effect on Gemini Flash-Lite is not measured yet.

## Measuring on a real model

Replay one fixed task set against the old and the new pipeline on the same provider and model, with model profiles
off. Record per task: verified completion, false completion (claimed done but the check or a human review says
otherwise), tool-call success, model and tool cycles, tokens, latency, recovery attempts, and the report counters
(`rejections`, `malformed`, `implicit`). Report simulated gate tests separately from live model results.
