# Default MCP services

A fresh installation with no saved MCP configuration uses the following five HTTP servers. Every server has `tools: ["*"]` and an empty `env`, exactly as shown. These are configuration defaults; connectivity and authentication are checked by the existing connection flow, not by the settings preview.

```json
{
  "mcpServers": {
    "context7": {
      "type": "http",
      "url": "https://mcp.context7.com/mcp",
      "tools": ["*"],
      "env": {}
    },
    "usefulai": {
      "type": "http",
      "url": "https://api.usefulai.fun/mcp",
      "tools": ["*"],
      "env": {}
    },
    "microsoft-learn": {
      "type": "http",
      "url": "https://learn.microsoft.com/api/mcp",
      "tools": ["*"],
      "env": {}
    },
    "github": {
      "type": "http",
      "url": "https://api.githubcopilot.com/mcp",
      "tools": ["*"],
      "env": {}
    },
    "mt-manager": {
      "type": "http",
      "url": "http://127.0.0.1:8787/mcp",
      "tools": ["*"],
      "env": {}
    }
  }
}
```

## Existing installations

Saved configurations remain authoritative, including an explicitly empty server map. Defaults are not merged into custom settings. Malformed saved data falls back to an empty configuration rather than enabling new external services.

In **Settings → MCP servers**, **Load default services** loads this configuration into the editable draft. Unsaved edits follow the existing discard confirmation. Review the draft and press **Save** to replace the stored configuration; loading the draft alone does not write preferences.

`127.0.0.1` refers to the Android device running OmniDev. Start MT Manager's local MCP service on port 8787 before connecting. An empty `env` contains no credentials; supply any credentials required by a service through the supported configuration/authentication mechanism. The defaults do not imply that an endpoint is authenticated or reachable.

## Discovery and response time

Discovery reuses per-server connections and caches successful tool schemas for five minutes. Failed or timed-out discovery is cached for one minute, so a missing local server or offline endpoint is not retried before every model request. Stale servers refresh concurrently with a three-second timeout per server, including initialization and pagination. HTTP cancellation cancels the underlying OkHttp call even while reading JSON or an open SSE stream. Tool execution retains its separate HTTP deadline.

Failed refresh removes stale routes and retries with a new connection after the failure backoff. Any configuration change, including credentials or tool filters, invalidates that server's cached session; removing a server removes its routes. Execution also checks the current configuration before using a route. Disabling tools for the chat suppresses discovery and execution. Runtime helpers and source tool counts are unchanged by discovery caching.

## Source and validation

`McpDefaults.kt` owns the default configuration, `McpConfigManager.kt` applies it only when the saved key is absent, and `McpSettingsViewModel.kt` exposes the draft action. `McpDefaultsTest.kt` verifies exact names/URLs/types/tool filters/empty environments, serialization, preservation of custom/empty settings and malformed saved-data handling.

Server count is separate from tool count: connected MCP servers advertise their tools dynamically. See the [built-in tool and skill catalog](TOOL_AND_SKILL_CATALOG.md).

`McpToolDiscoveryTest.kt` covers cache reuse, session reuse after TTL expiry, failure backoff, configuration/removal invalidation, concurrent healthy and hanging servers, and caller cancellation. `McpHttpCancellationTest.kt` uses a real local HTTP server with an unfinished response body to verify cancellation for both Streamable HTTP and legacy REST. It observes the underlying call terminating, rather than waiting for its 120-second deadline, and verifies that HTTP rejection is reported as failed discovery for both transports.
