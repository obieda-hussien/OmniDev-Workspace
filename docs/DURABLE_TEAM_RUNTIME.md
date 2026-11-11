# Durable Team runtime (P6)

P6 extends the real `SwarmOrchestrator` and the P5 completion protocol. It does not implement another worker pipeline,
change P0's acceptance rules, enable Team in Lite/Norm, or turn ordinary chat into a persistent task.

## Ownership and persistence

Room 20 adds one indexed `team_runs` table to `OmniDevDatabase`. Its envelope contains the Team ID, optional chat session
and update timestamp; its versioned Kotlin-serialization payload is a bounded Team aggregate. The additive 19→20 migration
is in `ALL_MIGRATIONS`; existing task, plan and invocation tables are unchanged. The existing 17→current and 18→current
regressions still run. P6 adds a real 19→20 reopen test and checks the complete migration graph from 1 through 19.
It does not claim historical user-data fixture coverage for every schema before 17.

`TeamStateEngine` applies identical rules over an in-memory or Room backend. Backend transforms are non-suspending;
Room transactions contain only local reads, validation and one write. Network, model and tool calls are always outside
transactions. The production chat, assistant, AgentRuntime/IPC and messaging coordinator factories use Room.
Messaging/IPC runs without a chat session have stable Team IDs but are not listed in an unrelated chat's recovery UI.

The original redacted request, scope, Team ID, immutable plan revisions, exact worker assignments and historical attempts
survive recreation. Each retry has a new attempt identity. Superseded attempts cannot publish into the current attempt.
A logical Team has one UUID owner and a 45-second lease, renewed every 10 seconds only while its coordinator runs.
Claims and all writes are serialized by Room; an unexpired owner cannot be replaced. After abrupt process death the user
may need to refresh after the old lease expires. A stale coordinator cannot dispatch another tool or publish a checkpoint.
The startup/listing path never executes a tool. There is no boot continuation or permanent background polling service.

## Execution and proof

The actual path is:

User objective → existing Team planner → persisted validated DAG → existing dependency waves → durable attempt start →
AgentPipeline → exact write-ahead operation → ordinary authorization/backend dispatch → observed tool result → P0 report →
P5 observation review → atomic worker checkpoint → dependency handoff → P5 Team report → durable verified result → chat.

An optional suspending `beforeToolExecution` hook runs in the existing AgentPipeline dispatcher after preflight and policy
checks, immediately before tool orchestration. It applies to native, text-envelope and MCP calls. If a durable write fails,
the tool is not dispatched. The giant `AgentPipeline.execute` coroutine is not expanded. P2 single-agent persistence is
unchanged; Team worker attempts are owned by their Team, rather than becoming independently resumable single-agent tasks.

A completed checkpoint contains the runtime observer's exact starts, observations and pending-effect flags, the P0 report,
output and digests. Restore reconstructs `WorkerCompletionObserver`, rechecks P0 through `WorkerEvidenceReview`, verifies
run/worker/attempt/output/ledger ownership, then calls the existing P5 receipt acceptance path in dependency order.
Model-authored JSON, discovery success, a generic operation success or worker prose cannot restore completion.
The final persisted completed state must match the Team report reconstructed from those durable receipts. Synthesis
remains presentation only. The bounded redacted final answer is saved atomically with the verified digest, so interruption
between completion and chat publication does not lose the result.

Compatible plan revisions can carry receipt references only when the user objective and exact worker contracts are
unchanged and the carried dependencies form a closed verified subgraph. Proof retains its original revision and attempt
identity. A real user redirect preserves historical proof as history; it cannot silently grant success to new contracts.
UNKNOWN prior operations block both plan changes and continuation.

## Recovery policy

| Persisted state | Explicit continuation |
| --- | --- |
| Planned worker, no execution | Start a fresh runtime attempt |
| Interrupted known read | Retry within remaining token/attempt budgets; old observations are context, not current proof |
| P0/P5-verified worker | Restore receipt and output, never rerun the worker |
| Non-read operation started, response lost | Pause for exact-operation reconciliation |
| Successful action without an accepted worker receipt | Remain blocked; do not repeat an action already recorded successful |
| Permission/approval intervention | Pause; user review permits a fresh attempt only when no unresolved effects remain, with normal permission gates reapplied |
| DNS/auth/backend outage | Preserve checkpoints and pause; avoid another worker-level speculative retry |
| Rate limit | Existing bounded provider backoff, then pause; no new unbounded retry loop |
| Context/schema/checkout errors | Preserve classification and observations; retain existing bounded correction/backend-routing policies |
| Interrupted synthesis | Restore all receipts and synthesize again, without redispatching tools |

A later read cannot reconcile a mutation. The review UI accepts an explicit statement, tied to the exact attempt and call,
that an uncertain operation did **not** execute. It never labels that worker completed. Known successful actions cannot
be declared not-executed. An authorization failure may be reconciled as not-executed while its intervention flag stays set;
only a subsequent explicit permission review can permit a fresh normally authorized attempt. This avoids a circular recovery barrier. If an action succeeded but its worker receipt was lost, P6 truthfully blocks continuation:
it does not infer a new P0 receipt from read-back or fabricate acceptance. Positive postcondition reconciliation and
continuing within such a partially executed mutation worker remain a P8 extension, not a P6 completion shortcut.

The saved-Team card shows the original/current objective, plan revision, dependency blockers, attempts, last attempted
operation, last verified tool result, unresolved effects and actual completion. Resume appears only when the stored
policy permits it. It reopens the same Team in the same session and scope, reselects providers and reapplies current
flavor/tool permissions. Cancel preserves history. Existing chat and console events remain available.

## Bounds and platform cost

At most 6 workers, 8 plan revisions, 6 physical attempts per worker/revision (including serial promotion), and 200 operation
records/attempt are allowed. Worker checkpoints cap outputs at 48,000 characters; partial observations cap each snippet
at 600 characters, with at most six snippets loaded as retry context. The aggregate has a 1,500,000-byte UTF-8 storage cap (also checked before decoding, below Android CursorWindow limits);
overflow fails closed. Session discovery loads at most 20 Teams. Quoted JSON/config credentials and URL userinfo are also redacted. Only whitelisted bounded action/resource targets are
saved for exact-operation review. No private reasoning, credentials, raw operation argument payloads,
full conversation history or full tool catalog is persisted here. Existing redaction, context compaction and actual usage
accounting remain in place.

Durable token reservations are conservative admission bounds, separate from measured provider usage. The full worker
allowance is reserved before starting an attempt; interrupted/unreported allowances are not refunded on restart. Planning
and synthesis also reserve bounded allowances. Consequently repeated app restarts cannot reset the Team's 2M ceiling,
but an interrupted Team may exhaust its conservative allowance before spending that many real tokens. This is deliberate.

The aggregate favors a small bounded DAG and short atomic writes. No device latency, memory or battery benchmark is
claimed; aggregate write cost and low-memory/process-death behavior must still be measured on the Infinix X689/API 30.

## Tests and phase boundaries

JVM tests use the production state engine with JSON recreation, ownership races, exact reconciliation, changed and
compatible revisions, stale/foreign receipts, approval review, budgets, bounded lists and deterministic dependencies.
Scripted end-to-end tests use the real SwarmOrchestrator, AgentPipeline, P0/P5 and ToolManager for repository inspection,
Kotlin mutation/read-back and Gradle verification. They interrupt after inspection, after the mutation receipt, during an
unknown mutation, during test execution, during synthesis and during a known read, and inject a provider DNS outage.
These are controlled scripted tool/provider results, not live GitHub operations or physical-device tests.

API 30 instrumentation includes actual Room reopen/concurrent claims/migration, exact receipt/final-result restoration,
and Compose safe-resume versus unknown-effect presentation. Existing P0–P5 and single-agent P2 tests remain enabled.
CI results must be read from the PR; source presence is not a passing test result.

P7 may advise tool correction using P4 contracts; it cannot authorize a call. P8 may add typed positive reconciliation
without manufacturing P0 proof. P9 specialists remain experimental/shadow-only and cannot mutate durable receipts or
lease safety policy. P10 can implement an authenticated backend behind the same write-ahead/exact-result boundary;
no desktop or cloud backend is required by P6.
