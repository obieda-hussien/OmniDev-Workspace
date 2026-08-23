package com.omnidev.workspace.data.tools

import android.content.Context
import android.util.Log

class McpAgentBridge(private val context: Context) {
    fun executeMcpCommand(server: String, tool: String, params: Map<String, Any>): String {
        Log.d("McpAgentBridge", "Executing MCP tool \$tool on server \$server")
        return "Executed \$tool on \$server with params: \$params"
    }
}
