package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.LauncherConnectionManager
import org.json.JSONObject

/**
 * Generates and renders Omni-Widgets on the launcher via launcher IPC.
 */
object WidgetGeneratorTool {

    private const val STRICT_SCHEMA_GUIDANCE = """
STRICT UI JSON SCHEMA (Material 3):
{"root": {"type": "Card", "children": [{"type": "Text", "text": "Hello", "style": "headlineMedium"}]}}

Supported component types only: Card, Column, Row, Text, Button, Spacer.
Return only schema-compliant JSON for ui_json.
"""

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "widget_generator_tool",
            description = "Generates and displays a dynamic UI widget directly on the user's home screen. " +
                "Use this when the user asks for a timer, a dashboard, a summary, or visual data.\n\n" +
                STRICT_SCHEMA_GUIDANCE,
            parameters = listOf(
                ToolParameter(
                    name = "widget_id",
                    type = "string",
                    description = "Unique widget identifier.",
                    required = true
                ),
                ToolParameter(
                    name = "ui_json",
                    type = "string",
                    description = "Structured UI layout JSON following the strict Material 3 schema and supported types.",
                    required = true
                )
            )
        )
    )

    fun execute(args: Map<String, String>): ToolExecutionResult {
        val widgetId = args["widget_id"]?.trim().orEmpty()
        if (widgetId.isBlank()) {
            return ToolExecutionResult(
                output = errorJson("Missing required parameter: widget_id"),
                isError = true
            )
        }

        val uiJson = args["ui_json"]?.trim().orEmpty()
        if (uiJson.isBlank()) {
            return ToolExecutionResult(
                output = errorJson("Missing required parameter: ui_json"),
                isError = true
            )
        }

        val rendered = LauncherConnectionManager.renderOmniWidget(
            widgetId = widgetId,
            composeJson = uiJson
        )

        return if (rendered) {
            ToolExecutionResult(
                output = JSONObject()
                    .put("ok", true)
                    .put("widget_id", widgetId)
                    .put("message", "Omni-Widget rendered successfully")
                    .toString()
            )
        } else {
            ToolExecutionResult(
                output = errorJson("Launcher rejected widget render request or launcher IPC is unavailable"),
                isError = true
            )
        }
    }

    private fun errorJson(message: String): String = JSONObject()
        .put("ok", false)
        .put("error", message)
        .toString()
}
