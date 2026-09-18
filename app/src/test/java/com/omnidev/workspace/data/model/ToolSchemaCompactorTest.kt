package com.omnidev.workspace.data.model

import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaCompactorTest {

    @Test
    fun `deduplicates tools and compacts verbose descriptions`() {
        val verbose = "This is an extremely verbose tool description. ".repeat(100)
        val tools = listOf(
            ToolDefinition("agent_runtime", verbose, listOf(ToolParameter("action", "string", verbose))),
            ToolDefinition("agent_runtime", "duplicate", emptyList()),
            ToolDefinition("other_tool", verbose, emptyList())
        )

        val result = ToolSchemaCompactor.compact(
            tools,
            listOf(ChatMessage(MessageRole.USER, "run python in the terminal"))
        ).orEmpty()

        assertEquals(2, result.size)
        assertEquals(1, result.count { it.name == "agent_runtime" })
        assertTrue(result.first { it.name == "agent_runtime" }.description.length < verbose.length)
        assertTrue(result.first { it.name == "agent_runtime" }.parameters.first().description.length <= 220)
    }

    @Test
    fun `pathological catalogs are bounded while must keep tools survive`() {
        val tools = buildList {
            repeat(120) { index ->
                add(ToolDefinition("tool_$index", "generic capability number $index", emptyList()))
            }
            add(ToolDefinition("privileged_tool", "Android privileged execution", emptyList()))
            add(ToolDefinition("agent_runtime", "developer terminal", emptyList()))
            add(ToolDefinition("search_knowledge", "memory search", emptyList()))
        }

        val result = ToolSchemaCompactor.compact(
            tools,
            listOf(ChatMessage(MessageRole.USER, "fix rish settings on Android"))
        ).orEmpty()

        assertTrue(result.size <= 80)
        assertTrue(result.any { it.name == "privileged_tool" })
        assertTrue(result.any { it.name == "agent_runtime" })
        assertTrue(result.any { it.name == "search_knowledge" })
    }
    @Test
    fun `Arabic SMS intent keeps specialized reader in oversized catalog`() {
        val tools = buildList {
            repeat(100) { index ->
                add(ToolDefinition("generic_tool_$index", "قدرة عامة رقم $index", emptyList()))
            }
            add(
                ToolDefinition(
                    "sms_reader_tool",
                    "Read and search device SMS messages with latest_search.",
                    emptyList()
                )
            )
            add(ToolDefinition("communicate_tool", "Send messages and calls.", emptyList()))
        }

        val result = ToolSchemaCompactor.compact(
            tools,
            listOf(ChatMessage(MessageRole.USER, "هات آخر رسالة أورنچ كاش واعرف الرصيد"))
        ).orEmpty()

        assertTrue(result.size <= 80)
        assertTrue(result.any { it.name == "sms_reader_tool" })
    }


    @Test
    fun `matched SMS reader is pinned even against many highly relevant competitors`() {
        val tools = buildList {
            repeat(160) { index ->
                add(
                    ToolDefinition(
                        "orange_cash_generic_$index",
                        "Read SMS messages and Orange Cash wallet inbox balance transactions",
                        emptyList()
                    )
                )
            }
            add(
                ToolDefinition(
                    "sms_reader_tool",
                    "Read and search device SMS with bounded Shizuku fallback.",
                    emptyList()
                )
            )
        }

        val result = ToolSchemaCompactor.compact(
            tools,
            listOf(ChatMessage(MessageRole.USER, "اقرأ رسائل اورنچ كاش واعرف آخر رصيد"))
        ).orEmpty()

        assertTrue(result.size <= 80)
        assertTrue(result.any { it.name == "sms_reader_tool" })
    }


}
