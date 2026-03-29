package com.omnidev.workspace

import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [FileToolManager] — validates file operations and scope enforcement.
 */
class FileToolManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var toolManager: FileToolManager
    private lateinit var scopePath: String

    @Before
    fun setup() {
        toolManager = FileToolManager()
        scopePath = tempDir.root.absolutePath
    }

    @Test
    fun `getToolDefinitions returns all 7 core tools`() {
        val manager = FileToolManager(godModeEnabled = false)
        val definitions = manager.getToolDefinitions()
        
        // التعديل هنا: نغير الرقم من 8 لـ 7
        assertEquals(7, definitions.size)
        
        val names = definitions.map { it.name }
        assertTrue(names.contains("read_file_lines"))
        assertTrue(names.contains("search_codebase"))
        assertTrue(names.contains("patch_file_content"))
        assertTrue(names.contains("create_file"))
        assertTrue(names.contains("delete_file"))
        assertTrue(names.contains("run_terminal"))
        assertTrue(names.contains("python_runner"))
        
        // التأكد إن web_search مابقتش موجودة هنا (اختياري للتأكيد)
        assertFalse(names.contains("web_search"))
    }

    @Test
    fun `read_file_lines reads specific line range`() = runTest {
        val file = tempDir.newFile("test.txt")
        file.writeText((1..100).joinToString("\n") { "Line $it" })

        val result = toolManager.executeTool(
            name = "read_file_lines",
            arguments = mapOf(
                "filePath" to file.absolutePath,
                "startLine" to "5",
                "endLine" to "10"
            ),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertTrue(result.output.contains("Line 5"))
        assertTrue(result.output.contains("Line 10"))
        assertFalse(result.output.contains("Line 4"))
        assertFalse(result.output.contains("Line 11"))
    }

    @Test
    fun `read_file_lines rejects path outside scope`() = runTest {
        val result = toolManager.executeTool(
            name = "read_file_lines",
            arguments = mapOf(
                "filePath" to "/etc/passwd",
                "startLine" to "1",
                "endLine" to "5"
            ),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("SCOPE VIOLATION"))
    }

    @Test
    fun `create_file creates new file with content`() = runTest {
        val filePath = "${scopePath}/newdir/newfile.kt"

        val result = toolManager.executeTool(
            name = "create_file",
            arguments = mapOf(
                "filePath" to filePath,
                "content" to "fun main() { println(\"Hello\") }"
            ),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertTrue(File(filePath).exists())
        assertEquals("fun main() { println(\"Hello\") }", File(filePath).readText())
    }

    @Test
    fun `create_file fails if file already exists`() = runTest {
        val file = tempDir.newFile("existing.txt")

        val result = toolManager.executeTool(
            name = "create_file",
            arguments = mapOf(
                "filePath" to file.absolutePath,
                "content" to "new content"
            ),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("already exists"))
    }

    @Test
    fun `patch_file_content replaces exact snippet`() = runTest {
        val file = tempDir.newFile("patch_test.kt")
        file.writeText("fun hello() {\n    println(\"Hello World\")\n}")

        val result = toolManager.executeTool(
            name = "patch_file_content",
            arguments = mapOf(
                "filePath" to file.absolutePath,
                "searchSnippet" to "Hello World",
                "replaceSnippet" to "OmniDev Workspace"
            ),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertTrue(file.readText().contains("OmniDev Workspace"))
        assertFalse(file.readText().contains("Hello World"))
    }

    @Test
    fun `patch_file_content fails on ambiguous match`() = runTest {
        val file = tempDir.newFile("ambiguous.txt")
        file.writeText("foo bar foo bar")

        val result = toolManager.executeTool(
            name = "patch_file_content",
            arguments = mapOf(
                "filePath" to file.absolutePath,
                "searchSnippet" to "foo",
                "replaceSnippet" to "baz"
            ),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("Ambiguous"))
    }

    @Test
    fun `delete_file removes file`() = runTest {
        val file = tempDir.newFile("to_delete.txt")
        assertTrue(file.exists())

        val result = toolManager.executeTool(
            name = "delete_file",
            arguments = mapOf("filePath" to file.absolutePath),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertFalse(file.exists())
    }

    @Test
    fun `search_codebase finds regex matches`() = runTest {
        val subdir = tempDir.newFolder("src")
        File(subdir, "Main.kt").writeText("fun main() {\n    println(\"Hello\")\n}")
        File(subdir, "Utils.kt").writeText("object Utils {\n    fun helper() = 42\n}")

        val result = toolManager.executeTool(
            name = "search_codebase",
            arguments = mapOf(
                "directory" to subdir.absolutePath,
                "regexPattern" to "fun \\w+"
            ),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertTrue(result.output.contains("Main.kt"))
        assertTrue(result.output.contains("Utils.kt"))
    }

    @Test
    fun `scope validation blocks traversal attacks`() = runTest {
        val result = toolManager.executeTool(
            name = "read_file_lines",
            arguments = mapOf(
                "filePath" to "${scopePath}/../../etc/passwd",
                "startLine" to "1",
                "endLine" to "5"
            ),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("SCOPE VIOLATION"))
    }
}
