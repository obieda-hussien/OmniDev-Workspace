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

## Source and validation

`McpDefaults.kt` owns the default configuration, `McpConfigManager.kt` applies it only when the saved key is absent, and `McpSettingsViewModel.kt` exposes the draft action. `McpDefaultsTest.kt` verifies exact names/URLs/types/tool filters/empty environments, serialization, preservation of custom/empty settings and malformed saved-data handling.

Server count is separate from tool count: connected MCP servers advertise their tools dynamically. See the [built-in tool and skill catalog](TOOL_AND_SKILL_CATALOG.md).
