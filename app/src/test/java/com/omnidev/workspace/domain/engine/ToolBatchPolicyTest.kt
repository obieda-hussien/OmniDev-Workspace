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
}
