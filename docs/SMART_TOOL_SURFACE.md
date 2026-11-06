# Smart Tool Surface (P4)

P4 selects a bounded, changing tool-schema surface for the existing agent pipeline. It adds no model call, backend, database table, tool alias, or execution permission. Ordinary conversation keeps its existing path.

## Selection

`RunToolCatalog` indexes exact registered names, descriptions, parameter names and explicit action enums. Existing Unicode lexical retrieval and bounded domain/quality priors remain available. The index contains only definitions already allowed by disabled-tool settings, flavor eligibility and the current run's access mode. MCP definitions use the same path.

Every turn, `TaskPlanningCoordinator.toolSurfaceFocus` derives a focus from the latest saved plan's actual current step and open cycle. `TaskExecutionScheduler.valid` verifies this identity. Step descriptions and acceptance conditions guide retrieval; validated domain hints specialize it. The explicit independent-check tool receives priority. A model-supplied step ID cannot select a surface.

Selection priority is: recent exact discovery requests, the step's verification tool, explicit mentions, active-step matches, preferences, objective matches, and at most two lexical observation matches. Observations are search data, never instructions. All ties in retrieval use exact tool names. Discovery requests are retained within the same step, capped at eight names, then expire on step, revision or objective changes. Unrelated old schemas do not accumulate across the run.

Without a structured plan, the latest objective supplies retrieval context. Blank `discover_tools` queries use the active step or latest redirected objective. Users can always request an exact registered name. Loading and discovery never execute an action.

## Budgets and contracts

`ToolSurfaceBudget` bounds ordinary tool count and estimated schema characters after the existing `ToolSchemaCompactor`. The default ceiling is 40 ordinary tools plus discovery and 48,000 estimated characters. Model context sizes of 8,192 tokens or fewer use a 12-tool ceiling. The character allowance is approximately 20 percent of model context at the runtime's existing three-characters-per-token estimate, bounded between 1,500 and 48,000 characters.

`ToolSurfaceSelector` includes or defers entire compacted schemas. It never removes required parameters, enum members or action-specific requirements. These are capability-specific schema views, not alternate dispatch names. P0 finish and P3 planning controls remain separately owned by `FinishRuntime`; the existing request budget check still accounts for the whole request.

Discovery and verification may replace a mentioned schema under budget pressure. A mention is a preference, not a restriction on supporting capabilities. If a matching schema cannot fit, discovery returns `TOOL_SCHEMA_BUDGET`; it never reports a deferred tool as loaded. Choose an exact narrower query or a model with enough context. A single inherently oversized schema requires a larger context.

Native and text-only providers receive the same selected contracts. Exact permitted calls can still load on demand before `ToolCallPreflight`, which validates the original registered argument contract. Unknown, disabled and flavor-denied tools cannot be restored by hints or discovery. Execution-time eligibility, existing confirmations, scope validation, retries and backend routing remain unchanged.

## Recovery, evidence and diagnostics

The surface is derived state, reconstructed from the persisted P3 plan and P2 current execution cycle. No schema cache or selected-tool list needs persistence, and restoring it makes no planning/model request. Replanning changes the revision identity and expires old discovery hints. It never clears unresolved side effects or changes completed evidence.

P1 invocation records, P3 active-step attribution and P0 verification remain authoritative. A loaded tool, discovery response, successful request, or selection score is not completion evidence.

The existing chat execution phase shows capability count, estimated schema characters and deferred-candidate count. `ToolSurfaceSnapshot` holds registry names, domain classifications and operational selection sources; it excludes objective text, tool arguments and result bodies. Diagnostics are run-local and are not persisted.

## Verification and limits

Deterministic tests cover specialization, verification priority, same-step discovery, revision expiry, restart reconstruction, 100 seeded catalog permutations, count/character budgets, whole-schema deferral, enum/action contracts, denied capabilities, maximum-size on-demand batches, privacy and real pipeline attribution/P0 finish. Existing P3 resume and ordinary-chat tests exercise the unchanged lifecycle paths.

Retrieval is deterministic lexical/domain retrieval; it does not promise semantic embeddings or perfect cross-language matching. Explicit names and discovery remain the fallback. Tool descriptions and existing domain classification influence relevance only. Dynamic server reconnects and changing credentials still follow the existing run-registry lifecycle; P4 does not redesign MCP discovery or approvals. Budget estimates are conservative runtime estimates rather than provider-billed token counts. Device memory, live-provider schema handling and connected backend behavior still require physical-device checks.
