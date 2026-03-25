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
            description = "Generates, removes, or clears dynamic UI widgets directly on the user's home screen. " +
                "Use 'render_widget' when the user asks for a timer, dashboard, or visual data. " +
                "Use 'remove_widget' to delete a specific widget. " +
                "Use 'clear_all_widgets' if the screen becomes too cluttered or if the user asks to remove all widgets.\n\n" +
                STRICT_SCHEMA_GUIDANCE,
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "One of: render_widget, remove_widget, clear_all_widgets. Defaults to render_widget.",
                    required = false
                ),
                ToolParameter(
                    name = "widget_id",
                    type = "string",
                    description = "Unique widget identifier. Required for render_widget and remove_widget.",
                    required = false
                ),
                ToolParameter(
                    name = "ui_json",
                    type = "string",
                    description = "Structured UI layout JSON following the strict Material 3 schema. Required only for render_widget.",
                    required = false
                )
            )
        )
    )

    fun execute(args: Map<String, String>): ToolExecutionResult {
        val action = args["action"]?.trim()?.lowercase() ?: "render_widget"
        val widgetId = args["widget_id"]?.trim().orEmpty()

        when (action) {
            "clear_all_widgets" -> {
                val cleared = LauncherConnectionManager.clearAllOmniWidgets()
                return if (cleared) {
                    ToolExecutionResult(
                        output = JSONObject()
                            .put("ok", true)
                            .put("action", action)
                            .put("message", "All Omni-Widgets cleared successfully")
                            .toString()
                    )
                } else {
                    ToolExecutionResult(
                        output = errorJson("Launcher rejected clear request or launcher IPC is unavailable"),
                        isError = true
                    )
                }
            }

            "remove_widget" -> {
                if (widgetId.isBlank()) {
                    return ToolExecutionResult(
                        output = errorJson("Missing required parameter: widget_id for action=remove_widget"),
                        isError = true
                    )
                }
                val removed = LauncherConnectionManager.removeOmniWidget(widgetId)
                return if (removed) {
                    ToolExecutionResult(
                        output = JSONObject()
                            .put("ok", true)
                            .put("action", action)
                            .put("widget_id", widgetId)
                            .put("message", "Omni-Widget removed successfully")
                            .toString()
                    )
                } else {
                    ToolExecutionResult(
                        output = errorJson("Launcher rejected remove request or launcher IPC is unavailable"),
                        isError = true
                    )
                }
            }

            "render_widget" -> {
                if (widgetId.isBlank()) {
                    return ToolExecutionResult(
                        output = errorJson("Missing required parameter: widget_id for action=render_widget"),
                        isError = true
                    )
                }

                val uiJson = args["ui_json"]?.trim().orEmpty()
                if (uiJson.isBlank()) {
                    return ToolExecutionResult(
                        output = errorJson("Missing required parameter: ui_json for action=render_widget"),
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
                            .put("action", action)
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

            else -> {
                return ToolExecutionResult(
                    output = errorJson("Unsupported action '$action'. Valid actions: render_widget, remove_widget, clear_all_widgets"),
                    isError = true
                )
            }
        }
    }

    private fun errorJson(message: String): String = JSONObject()
        .put("ok", false)
        .put("error", message)
        .toString()
}
