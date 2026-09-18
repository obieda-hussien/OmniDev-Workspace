package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBatchPolicyTest {

    private fun call(name: String, args: Map<String, String> = emptyMap()) =
        ToolCall(id = name, name = name, arguments = args)

    @Test
    fun `independent reads may run concurrently`() {
        assertTrue(
            ToolBatchPolicy.canRunBatchInParallel(
                listOf(
                    call("read_file_lines", mapOf("path" to "a.kt")),
                    call("search_codebase", mapOf("query" to "Room"))
                )
            )
        )
    }

    @Test
    fun `two writes are always serialized`() {
        assertFalse(
            ToolBatchPolicy.canRunBatchInParallel(
                listOf(call("write_file"), call("patch_file_content"))
            )
        )
    }

    @Test
    fun `mixed read and mutation is serialized`() {
        assertFalse(
            ToolBatchPolicy.canRunBatchInParallel(
                listOf(call("read_file_lines"), call("run_terminal"))
            )
        )
    }

    @Test
    fun `semantic ui reads can overlap but actions cannot`() {
        assertTrue(
            ToolBatchPolicy.canRunBatchInParallel(
                listOf(
                    call("semantic_ui", mapOf("action" to "dump_tree")),
                    call("semantic_ui", mapOf("action" to "find_node"))
                )
            )
        )
        assertFalse(
            ToolBatchPolicy.canRunBatchInParallel(
                listOf(
                    call("semantic_ui", mapOf("action" to "dump_tree")),
                    call("semantic_ui", mapOf("action" to "click"))
                )
            )
        )
    }

    @Test
    fun `unknown mcp mutation is conservative`() {
        assertFalse(
            ToolBatchPolicy.canRunBatchInParallel(
                listOf(call("mcp_github_create_pull_request"), call("mcp_github_update_file"))
            )
        )
    }
    @Test
    fun `content query through run terminal is read only but pm grant is not`() {
        assertTrue(
            ToolBatchPolicy.isReadOnly(
                call(
                    "run_terminal",
                    mapOf("command" to "content query --uri content://sms --projection body,date")
                )
            )
        )
        assertFalse(
            ToolBatchPolicy.isReadOnly(
                call(
                    "run_terminal",
                    mapOf("command" to "pm grant com.example android.permission.READ_SMS")
                )
            )
        )
    }

    @Test
    fun `SMS reader is read only while screenshot stays at most once`() {
        assertTrue(
            ToolBatchPolicy.isReadOnly(
                call("sms_reader_tool", mapOf("action" to "latest_search", "query" to "OrangeCash"))
            )
        )
        assertFalse(ToolBatchPolicy.isReadOnly(call("screenshot_tool")))
    }

    @Test
    fun `MCP mutation marker overrides read marker`() {
        assertFalse(ToolBatchPolicy.isReadOnly(call("mcp_example_get_and_delete")))
        assertFalse(ToolBatchPolicy.isReadOnly(call("mcp_github_create_pull_request")))
        assertTrue(ToolBatchPolicy.isReadOnly(call("mcp_example_get_status")))
    }


}
