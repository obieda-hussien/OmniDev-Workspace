1. **Create MCP Configuration Schema and Manager:**
   - Check if `app/src/main/java/com/omnidev/workspace/domain/mcp` and `app/src/main/java/com/omnidev/workspace/data/mcp` directories exist, create them if not.
   - Create `app/src/main/java/com/omnidev/workspace/domain/mcp/McpConfig.kt` to hold data classes: `McpServerConfig` (type, url, tools, env) and `McpConfig` (map of server names to configs).
   - Create `app/src/main/java/com/omnidev/workspace/data/mcp/McpConfigManager.kt` to handle CRUD operations (`addServer`, `removeServer`, `updateServer`, `getServers`).
   - `McpConfigManager` should use `EncryptedSharedPreferences` for secure storage and expose a `StateFlow<McpConfig>` for reactivity.

2. **Implement MCP HTTP Transport Layer:**
   - Ensure `okhttp-sse` is available in `app/build.gradle.kts`. If not, add the dependency.
   - Create `app/src/main/java/com/omnidev/workspace/data/mcp/McpHttpClient.kt`.
   - Implement the `initialize` method to connect to the SSE endpoint and receive the client ID/session.
   - Implement `tools/list` to fetch remote tools via POST request.
   - Implement `tools/call` to execute a tool.
   - Ensure the HTTP client dynamically attaches headers from `McpServerConfig.env` (e.g. `Authorization: Bearer <key>`) for every request.

3. **Implement MCP Registry for Agent Integration:**
   - Create `app/src/main/java/com/omnidev/workspace/domain/mcp/McpRegistry.kt`.
   - Implement `fetchAllAvailableTools()` which iterates over configured servers from `McpConfigManager`, calls `McpHttpClient.fetchTools()`, and translates the JSON schema to Omni's `ToolDefinition` schema.
   - Translation logic must handle types (string, integer, boolean), nested objects, and `required` arrays flawlessly.
   - Prefix tool names with `mcp_[server_name]_` during translation.

4. **Update AgentPipeline.kt for Dynamic Tool Registration and Routing:**
   - Inject `McpRegistry` and `McpHttpClient` into `AgentPipeline.kt`.
   - Before executing the LLM completion request in `execute()`, fetch dynamic tools via `McpRegistry.fetchAllAvailableTools()` and append them to `toolDefs`.
   - Add the `[DYNAMIC MCP TOOLS AWARENESS]` block to the agent's base system prompt.
   - Intercept tool calls starting with `mcp_` inside the ReAct loop's tool execution phase. Route them to `McpHttpClient.executeTool()`.
   - Wrap the MCP execution in a `try/catch` to gracefully return errors like `"MCP Tool Error: Connection timeout"` instead of crashing the pipeline.

5. **Verify and Test:**
   - Run `./gradlew test` to ensure no regressions.
   - Check compilation by running `./gradlew assembleDebug`.

6. **Complete pre-commit steps:**
   - Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.
