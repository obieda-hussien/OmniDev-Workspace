# Tool execution contract

The model proposes operations. The runtime decides whether a proposal matches a currently exposed, permitted tool contract before executing it. This boundary applies to Agent, Team workers, Chat web calls and direct CompositeToolManager dispatch.

## Catalog and retrieval

`RunToolCatalog` indexes real, permitted local and connected definitions once per run. It scores exact names, Unicode words in names/descriptions/parameters, inverse document frequency, document length, task domains and a small advisory historical-quality signal. Exact name matches dominate; long general-purpose tool descriptions do not get an unlimited relevance advantage.

Agent starts with at most 24 operation schemas plus `discover_tools`. Before every model request the runtime retrieves up to six matches for the original routing context and up to six for the latest short, redacted observation. It updates the bounded schema set without requiring a model-issued discovery call. Observations are retrieval data, not instructions or permission. A manual discovery returns up to six real contracts and loads them for the **next** model request. At most 40 operation schemas stay loaded. Disabled tools and flavor-denied capabilities are excluded before indexing; preferences and retrieval cannot restore them. The catalog is local to the run and to each worker. Discovery never redirects a guessed name to another operation and never executes its matches.

`AutomaticToolRouter` allows at most two retrieval recovery attempts per run, shared by preflight errors and explicit unavailable-tool replies. Failed calls are not automatically replayed or repaired. A model must submit a new proposal after reading the current schemas. A final reply after an unresolved failure receives bounded corrective retrieval before being rejected as unverified. Missing-tool replies are recognized by a small English/Arabic phrase list, not a universal semantic classifier. Existing iteration/time/token budgets continue to apply.

The full local catalog is cached per CompositeToolManager instance; flavor checks remain dynamic. Tool access changes are rechecked immediately before dispatch.

## Preflight and execution

`ToolCallPreflight` checks exact names, parameter keys, required parameters, scalar/container types, explicitly declared action enums and action-dependent required parameters. Empty file content remains valid. Action/operation selectors cannot be blank. Enum constraints also survive HTTP/Streamable MCP catalog ingestion and native provider schema conversion.

`ToolArgumentCodec` preserves transport decoding errors. Invalid JSON, non-object input and null arguments cannot silently become a valid empty argument map. Provider call IDs and Gemini opaque metadata remain unchanged.

`ValidatedToolBatchExecutor` validates the entire response before any dispatcher runs:

- One invalid/unexposed call or denied capability blocks the whole batch.
- IDs must be nonempty and unique; duplicate mutations are refused.
- A response may contain at most eight calls, with at most four independent reads in flight.
- Equivalent reads share one result within a batch. A mutation clears this reuse, so subsequent verification reads see fresh state. There is no cross-turn observation cache. Normal inter-turn pacing is reduced to 0/50/100 ms for Fast/Executor/Orchestrator; provider rate-limit backoff remains active.
- Following a sequential failure, later mutations wait for a new observation/plan; reads can still inspect the failure.
- Unknown effects, including arbitrary MCP tools, are serialized and are not automatically retried based on a name containing `get` or `read`.

Three consecutive malformed batches stop the run. A model's final success claim immediately after an unresolved failed batch is rejected as unverified. A successful discovery alone does not resolve an execution failure.

## Models without native function calling

`TextToolCallAdapter` accepts one explicit whole-response envelope:

```json
{"omni_tool_call":{"name":"discover_tools","arguments":{"query":"read a project file"}}}
```

Ordinary prose, embedded examples and unrelated JSON are not scanned for commands. Attempted malformed envelopes produce correction feedback. Native calls take precedence when present.

A simpler alternative omits the tool name:

```json
{"omni_operation":{"intent":"Search messages","arguments":{"query":"Ahmed"}}}
```

The local router matches this explicit whole-response proposal against permitted real definitions. Exact names take precedence; semantic matching needs at least two overlapping terms, 60% query coverage and a clear score margin over the runner-up. Domain or historical-quality priors cannot authorize a match. Ties, weak evidence, missing definitions and malformed envelopes yield corrective feedback without execution. Candidates are loaded for the next request. A unique match not exposed in the current request also requires resubmission after schema loading. Arguments remain unchanged and pass normal whole-batch preflight and worker/backend authorization. Lexical uniqueness is not proof of correct user-intent interpretation.

For models whose metadata disables function calling, and for Local Edge, `ToolTextProtocol` includes the current schemas in the text prompt and projects prior proposals/observations into ordinary messages. No unsupported native tool payload is sent. The resulting proposal goes through the exact same preflight and authorization. Prompt and catalog costs count toward admission and token budgets.

## Team scheduling

Parallel-safe workers have a runtime read-only call guard; the planner's label alone cannot authorize mutation. Tasks requiring connected tools without trusted effect metadata serialize. If a read worker requests mutation, its batch is blocked before dispatch. Once the read wave finishes, it can resume once as a serial worker only when the original objective carries mutation intent according to the existing conservative task classifier. Read-only objectives fail that task instead of promoting it. Actual tool/backend authorization remains mandatory. Recovery uses the remaining team token ceiling.

## Voice teardown crash

`SpeechOutputLifecycle` owns one Android TextToSpeech connection. It detaches the connection before shutdown, closes it once, releases an engine arriving after close, prevents stale utterance cleanup from touching a closed connection, and tolerates Android's `IllegalArgumentException: Service not registered` and disconnected-state cleanup errors. Stop failure does not prevent shutdown. LocalSpeechOutput binds using application context and completes pending initialization/speech on close. Service destruction and session finalization share the release path.

## Verification and limits

Regression coverage includes invented names, schema mistakes, malformed input, disabled catalogs, discovery boundaries, batch atomicity, concurrency bounds, fresh verification reads, plain-text model execution, unsupported completion claims, Team serial recovery and repeated/racing voice cleanup. Android-dependent optional services are isolated in local JVM verification; full application compilation/unit tests still run in Android CI.

This improves executable-contract reliability. It does not prove that a schema-valid operation matches every user intention, that a backend is available, or that a model can solve an unfamiliar task. Nested MCP payload semantics remain the connected server's responsibility; the local flat ToolParameter contract does not implement full JSON Schema. Live Gemini quality/latency and the Infinix TTS lifecycle still require representative provider/device runs. No zero-error or measured model-quality uplift is claimed.

Design references: [Gemini function calling](https://ai.google.dev/gemini-api/docs/function-calling), [Anthropic tool search](https://www.anthropic.com/engineering/advanced-tool-use).
