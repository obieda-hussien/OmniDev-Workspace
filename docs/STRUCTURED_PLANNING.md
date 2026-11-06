# Structured planning and adaptive execution (P3)

Persistent agent tasks can decompose a complex request into a validated dependency plan. Ordinary chat stays outside
the task lifecycle. Atomic requests retain the existing minimal plan and make no planning model request. A request
with three deliverables, three acceptance items, or sufficient breadth/length uses structured generation.

## Generation and validation

`StructuredPlanGenerator` uses the same completion provider, credentials, model role and routing abstraction as the
run. `CompletionRequest` currently has no native response-schema option: the generator requests a JSON object and
decodes it strictly with the existing Kotlin serialization dependency. It accepts no prose extraction, tool calls,
unknown fields or hidden reasoning. One bounded schema repair is allowed. Failed generation leaves the prior plan
intact; a new task without a valid plan cannot dispatch work. The model can return one valid verification step when
it cannot decompose further. All generation/repair usage is charged through `TokenAccounting` and admitted against
the run budget and model context window. No global temperature or tool-choice setting changes.

Input contains a redacted objective, immutable original request excerpt, mandatory constraints, at most two recent
user messages, grouped capability names, the previous plan, completed keys and three bounded failure statuses.
It excludes full tool schemas, raw arguments/results, credentials and private model reasoning. Text and payloads are
bounded. `PlanValidator` rejects duplicate keys/dependencies, missing references, self edges, cycles, duplicate normalized
descriptions, unsupported approval/domain metadata, unsafe payloads, empty verification contracts and omitted mandatory
constraints. Equivalent DAGs normalize with lexicographic tie-breaking. Validation runs again inside the store before
any supersession write. Constraint checks are exact structural checks; they do not establish semantic equivalence of
arbitrary natural-language instructions.

## Persistence

P1's step rows remain the only source of step descriptions, dependencies, expected output, conditions, statuses and
evidence. Room version 19 adds one `agent_task_plans.metadataJson` column with default empty string, preserving old
rows. This additive column stores the missing per-revision objective, summary, constraints, per-key verification and
approval hints, and a runtime source-objective digest. Encoding this data into a description/reason would corrupt
existing contracts or lose metadata on recovery. No new table, database, backend or dependency is introduced.

`TaskStateEngine` supplies the same rules to Room and memory. An optional expected-revision compare-and-set prevents
concurrent stale planners from committing. Event keys and identical normalized content make retries idempotent.
Completed steps carry forward only with identical descriptions, dependencies, outputs, conditions and policies;
their original invocation ids remain unchanged. Old unfinished steps are explicitly superseded as SKIPPED, never
completed. Prior revisions and unresolved operations remain in the original task.

## Scheduling, attribution and verification

`TaskExecutionScheduler` selects one dependency-ready step deterministically. Only COMPLETED steps with recorded
proof satisfy dependencies. FAILED, SKIPPED, missing or incomplete dependencies cannot unlock work. Approval waits
reuse existing task/step statuses and `ConfirmationGate`/`ConfirmationKind`; plan hints never bypass actual tool gates.
Structured tool batches are sequential. A failed invocation marks its active step failed, preventing automatic spin.

Each persisted cycle binds a runtime-owned active step. `TaskRunRecorder` assigns intents from that immutable context,
not the first unfinished step at result time. The store checks task, cycle and current revision ownership; fabricated
step ids fail before insertion. Discovery calls stay unassigned and cannot prove a step. Tool retries remain in the
same context. Historical provider call ids are reserved during P2 resume so new calls cannot impersonate old evidence.

The model proposes `complete_plan_step` alone with the active key and exact persistent invocation ids. It cannot set
a status. `StepEvidenceEvaluator` scopes P0 `FinishVerifier` to the assigned invocations, then enforces typed checks:

| Proof | Required evidence |
| --- | --- |
| READ_RESULT | Nonempty successful read-only result, optional exact tool/content predicate |
| READ_BACK | Successful action, later exact-path file read-back through P0, and nonempty requested-content predicate |
| INDEPENDENT_CHECK | Successful action, later successful read-only check tool, and nonempty final-state predicate |

Any side effect rejects READ_RESULT proof. Transport acknowledgement, backend acceptance, a nonblank generic
`ToolExecutionResult.verification`, file existence and a model statement are insufficient. Mutation and verification
belong to the same step so the verifier is not blocked behind an unverified mutation dependency. An explicit proposal
is required; a tool success does not automatically complete a step. Natural-language conditions still require a
meaningful machine-checkable predicate; the system cannot prove arbitrary business semantics from free-form text.

P0 `FinishGate` remains the sole task completion gate. Its verification context includes unverified plan steps and
unresolved effects, so even a convincing `finish_task` or plain-text final cannot bypass the plan. The store also
refuses an inconsistent completed structured task. There is no second task finish protocol.

## Replanning and recovery

`AdaptiveReplanner` handles execution failures, user redirects and explicit discovery/dependency-change requests.
Generation is outside transactions; a validated revision commits atomically. At most three planning episodes occur
per run, with identical episodes/content suppressed. UNKNOWN effects block replanning and completion until P2 reconciles
the exact invocation. A later read never performs reconciliation. Mandatory constraints accumulate conservatively;
the planner cannot silently relax them.

P2 loads the latest revision, preserves completed evidence and recomputes readiness. Restart alone never generates
another plan. An interrupted uncommitted generation can retry safely. Resume-with-draft updates the structured task's
saved objective; its source digest triggers a genuine redirect revision on pipeline entry. P0 acceptance policy and
original acceptance items use the existing task `constraintsJson`. Task/session identity and original request remain
unchanged. Legacy minimal plans continue through P0; legacy multi-step plans can be reviewed/scheduled without being
regenerated merely because the process restarted.

## Presentation and validation boundaries

The original chat's progress/recovery card shows the plan revision, active step, dependency states, expected outputs,
verification conditions, proof counts, approval hints and revision reason. It never displays private reasoning.
Lite retains its denial-only autonomous-engine boundary. Existing tool backends, tier permissions and normal chat
behavior remain in place.

Deterministic JVM tests cover generated/invalid plans, seeded DAGs, scheduling, attribution, output proof, history,
CAS concurrency, interrupted generation, redirects, UNKNOWN effects and resume. Scripted real-pipeline tests exercise
generation through P0 finalization. API 30 tests cover Compose presentation, Room/memory equivalence, reopen and the
18-to-19 migration; the existing 17-to-current migration test remains active. Provider fixtures are not live-provider
results. Physical-device process death, low-memory behavior and real provider plan quality require device testing.

Preflight: P2's exact-text recovery regression was repaired independently in PR #226, merged as
`e5a74a0c952f166178136a26f52fffdbf9dcacef`. Its unchanged API 30 recovery suite and all-flavor JVM tests passed before P3
started. No P2 repair is included in the P3 branch.
