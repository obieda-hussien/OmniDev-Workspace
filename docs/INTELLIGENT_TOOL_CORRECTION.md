# Tool correction contracts (P7)

ToolCorrectionAdvisor interprets invalid proposals against only caller-provided eligible contracts. ToolCallPreflight
uses its typed bounded diagnostics in the actual Agent, Team, Chat and CompositeToolManager validation paths. It does
not dispatch, grant permission, mutate a ToolCall, widen the tool surface, repair JSON or create execution evidence.

A unique close registered name, parameter name or enum value can be suggested. Case-only errors and adjacent
transpositions are supported. Ambiguous, absent, duplicated-registry and distant candidates abstain. An action-value hint
can reveal its dependent required fields in the same report, without adopting the corrected action for execution.
Missing fields and invalid scalar/container types are also identified together, rather than one model turn per field.

Reports contain typed operational categories and registered schema names, not proposed values or unknown parameter names.
At most 12 issues and 2,400 rendered characters are returned. The caller must submit a new exact call; whole-batch
validation and execution-time authorization still run normally. A valid peer never executes beside an invalid batch
member. Existing P0/P2/P5/P6 evidence, UNKNOWN restrictions, ownership and provider paths are unchanged.

This is deterministic correction, not a neural confidence claim. Neural ranking remains experimental and cannot authorize
calls. Existing P4 retrieval and backend-specific recovery retain ownership of catalog loading and execution routing.
