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
    fun `getToolDefinitions returns all 15 core tools`() {
        val manager = FileToolManager(godModeEnabled = false)
        val definitions = manager.getToolDefinitions()

        assertEquals(15, definitions.size)

        val names = definitions.map { it.name }
        assertTrue(names.contains("read_file_lines"))
        assertTrue(names.contains("search_codebase"))
        assertTrue(names.contains("patch_file_content"))
        assertTrue(names.contains("multi_read"))
        assertTrue(names.contains("multi_patch_file_content"))
        assertTrue(names.contains("delete_text"))
        assertTrue(names.contains("clear_file"))
        assertTrue(names.contains("delete_lines"))
        assertTrue(names.contains("insert_lines"))
        assertTrue(names.contains("replace_lines"))
        assertTrue(names.contains("append_to_file"))
        assertTrue(names.contains("create_file"))
        assertTrue(names.contains("delete_file"))
        assertTrue(names.contains("run_terminal"))
        assertTrue(names.contains("python_runner"))

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

    // ── multi_read tests ──────────────────────────────────────────────────────

    @Test
    fun `multi_read reads multiple files`() = runTest {
        val file1 = tempDir.newFile("multi1.txt")
        val file2 = tempDir.newFile("multi2.txt")
        file1.writeText("Hello from file1")
        file2.writeText("Hello from file2")

        val filePaths = """["${file1.absolutePath}","${file2.absolutePath}"]"""
        val result = toolManager.executeTool(
            name = "multi_read",
            arguments = mapOf("filePaths" to filePaths),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertTrue(result.output.contains("Hello from file1"))
        assertTrue(result.output.contains("Hello from file2"))
    }

    @Test
    fun `multi_read with line range returns only requested lines`() = runTest {
        val file = tempDir.newFile("ranged.txt")
        file.writeText((1..20).joinToString("\n") { "Line $it" })

        val filePaths = """["${file.absolutePath}"]"""
        val result = toolManager.executeTool(
            name = "multi_read",
            arguments = mapOf(
                "filePaths" to filePaths,
                "startLine" to "3",
                "endLine" to "5"
            ),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertTrue(result.output.contains("Line 3"))
        assertTrue(result.output.contains("Line 5"))
        assertFalse(result.output.contains("Line 2"))
        assertFalse(result.output.contains("Line 6"))
    }

    @Test
    fun `multi_read returns error for invalid JSON`() = runTest {
        val result = toolManager.executeTool(
            name = "multi_read",
            arguments = mapOf("filePaths" to "not-a-json-array"),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("filePaths must be a valid JSON array"))
    }

    @Test
    fun `multi_read enforces max files limit`() = runTest {
        val paths = (1..21).joinToString(",") { "\"$scopePath/file$it.txt\"" }
        val result = toolManager.executeTool(
            name = "multi_read",
            arguments = mapOf("filePaths" to "[$paths]"),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("Too many files"))
    }

    @Test
    fun `multi_read handles non-string path entries gracefully`() = runTest {
        val result = toolManager.executeTool(
            name = "multi_read",
            arguments = mapOf("filePaths" to "[123, 456]"),
            scopePath = scopePath
        )

        // org.json coerces numeric entries to "123", "456"; they resolve as non-existent files
        assertFalse(result.isError)
        assertTrue(result.output.contains("File not found") || result.output.contains("Invalid path"))
    }

    // ── multi_patch_file_content tests ────────────────────────────────────────

    @Test
    fun `multi_patch_file_content applies all patches`() = runTest {
        val file1 = tempDir.newFile("patch1.kt")
        val file2 = tempDir.newFile("patch2.kt")
        file1.writeText("val x = 1")
        file2.writeText("val y = 2")

        val operations = """[
            {"filePath":"${file1.absolutePath}","searchSnippet":"val x = 1","replaceSnippet":"val x = 100"},
            {"filePath":"${file2.absolutePath}","searchSnippet":"val y = 2","replaceSnippet":"val y = 200"}
        ]"""

        val result = toolManager.executeTool(
            name = "multi_patch_file_content",
            arguments = mapOf("operations" to operations),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertEquals("val x = 100", file1.readText())
        assertEquals("val y = 200", file2.readText())
    }

    @Test
    fun `multi_patch_file_content returns error for invalid JSON`() = runTest {
        val result = toolManager.executeTool(
            name = "multi_patch_file_content",
            arguments = mapOf("operations" to "not-valid-json"),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("operations must be a valid JSON array"))
    }

    @Test
    fun `multi_patch_file_content enforces max operations limit`() = runTest {
        val ops = (1..21).joinToString(",") {
            """{"filePath":"$scopePath/f$it.txt","searchSnippet":"x","replaceSnippet":"y"}"""
        }
        val result = toolManager.executeTool(
            name = "multi_patch_file_content",
            arguments = mapOf("operations" to "[$ops]"),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("Too many patch operations"))
    }

    @Test
    fun `multi_patch_file_content reports partial failure`() = runTest {
        val goodFile = tempDir.newFile("good_patch.kt")
        goodFile.writeText("val a = 1")

        val operations = """[
            {"filePath":"${goodFile.absolutePath}","searchSnippet":"NONEXISTENT","replaceSnippet":"x"}
        ]"""

        val result = toolManager.executeTool(
            name = "multi_patch_file_content",
            arguments = mapOf("operations" to operations),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("Patch operation 1 failed"))
    }

    @Test
    fun `multi_patch_file_content returns error for operation missing required fields`() = runTest {
        val result = toolManager.executeTool(
            name = "multi_patch_file_content",
            arguments = mapOf("operations" to """[{"filePath":"some/path.kt"}]"""),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("filePath, searchSnippet, and replaceSnippet"))
    }

    // ── delete_text tests ─────────────────────────────────────────────────────

    @Test
    fun `delete_text removes all occurrences by default`() = runTest {
        val file = tempDir.newFile("del_all.txt")
        file.writeText("foo bar foo baz foo")

        val result = toolManager.executeTool(
            name = "delete_text",
            arguments = mapOf("filePath" to file.absolutePath, "text" to "foo"),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertFalse(file.readText().contains("foo"))
    }

    @Test
    fun `delete_text removes only first occurrence`() = runTest {
        val file = tempDir.newFile("del_first.txt")
        file.writeText("foo bar foo baz")

        val result = toolManager.executeTool(
            name = "delete_text",
            arguments = mapOf("filePath" to file.absolutePath, "text" to "foo", "occurrences" to "first"),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        val text = file.readText()
        assertTrue(text.contains("foo")) // second occurrence still present
        assertFalse(text.startsWith("foo"))
    }

    @Test
    fun `delete_text removes only last occurrence`() = runTest {
        val file = tempDir.newFile("del_last.txt")
        file.writeText("foo bar foo baz")

        val result = toolManager.executeTool(
            name = "delete_text",
            arguments = mapOf("filePath" to file.absolutePath, "text" to "foo", "occurrences" to "last"),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        val text = file.readText()
        assertTrue(text.startsWith("foo")) // first occurrence still present
        assertFalse(text.trimEnd().endsWith("foo"))
    }

    @Test
    fun `delete_text restricted to specific line`() = runTest {
        val file = tempDir.newFile("del_line.txt")
        file.writeText("keep foo here\nremove foo here\nkeep foo here")

        val result = toolManager.executeTool(
            name = "delete_text",
            arguments = mapOf("filePath" to file.absolutePath, "text" to "foo", "lineNumber" to "2"),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        val lines = file.readText().lines()
        assertTrue(lines[0].contains("foo"))   // line 1 untouched
        assertFalse(lines[1].contains("foo"))  // line 2 cleared
        assertTrue(lines[2].contains("foo"))   // line 3 untouched
    }

    @Test
    fun `delete_text returns error when text not found`() = runTest {
        val file = tempDir.newFile("del_notfound.txt")
        file.writeText("hello world")

        val result = toolManager.executeTool(
            name = "delete_text",
            arguments = mapOf("filePath" to file.absolutePath, "text" to "NONEXISTENT"),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("not found"))
    }

    @Test
    fun `delete_text returns error for invalid occurrences value`() = runTest {
        val file = tempDir.newFile("del_invalid.txt")
        file.writeText("foo")

        val result = toolManager.executeTool(
            name = "delete_text",
            arguments = mapOf("filePath" to file.absolutePath, "text" to "foo", "occurrences" to "second"),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("occurrences"))
    }

    // ── clear_file tests ──────────────────────────────────────────────────────

    @Test
    fun `clear_file empties file content`() = runTest {
        val file = tempDir.newFile("to_clear.txt")
        file.writeText("lots of content here")

        val result = toolManager.executeTool(
            name = "clear_file",
            arguments = mapOf("filePath" to file.absolutePath),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertTrue(file.exists())
        assertEquals("", file.readText())
    }

    @Test
    fun `clear_file returns error for missing file`() = runTest {
        val result = toolManager.executeTool(
            name = "clear_file",
            arguments = mapOf("filePath" to "$scopePath/no_such_file.txt"),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("File not found"))
    }

    // ── delete_lines tests ────────────────────────────────────────────────────

    @Test
    fun `delete_lines removes single line`() = runTest {
        val file = tempDir.newFile("del_line_single.txt")
        file.writeText("line1\nline2\nline3\nline4")

        val result = toolManager.executeTool(
            name = "delete_lines",
            arguments = mapOf("filePath" to file.absolutePath, "startLine" to "2"),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        val lines = file.readText().lines()
        assertEquals(3, lines.size)
        assertFalse(lines.contains("line2"))
        assertTrue(lines.contains("line1"))
        assertTrue(lines.contains("line3"))
    }

    @Test
    fun `delete_lines removes a range`() = runTest {
        val file = tempDir.newFile("del_range.txt")
        file.writeText("line1\nline2\nline3\nline4\nline5")

        val result = toolManager.executeTool(
            name = "delete_lines",
            arguments = mapOf("filePath" to file.absolutePath, "startLine" to "2", "endLine" to "4"),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        val lines = file.readText().lines()
        assertEquals(2, lines.size)
        assertEquals("line1", lines[0])
        assertEquals("line5", lines[1])
    }

    @Test
    fun `delete_lines returns error for out-of-range startLine`() = runTest {
        val file = tempDir.newFile("del_oor.txt")
        file.writeText("line1\nline2")

        val result = toolManager.executeTool(
            name = "delete_lines",
            arguments = mapOf("filePath" to file.absolutePath, "startLine" to "99"),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("beyond end of file"))
    }

    // ── insert_lines tests ────────────────────────────────────────────────────

    @Test
    fun `insert_lines inserts before specified line`() = runTest {
        val file = tempDir.newFile("insert.txt")
        file.writeText("line1\nline3")

        val result = toolManager.executeTool(
            name = "insert_lines",
            arguments = mapOf(
                "filePath" to file.absolutePath,
                "content" to "line2",
                "lineNumber" to "2"
            ),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        val lines = file.readText().lines()
        assertEquals(3, lines.size)
        assertEquals("line1", lines[0])
        assertEquals("line2", lines[1])
        assertEquals("line3", lines[2])
    }

    @Test
    fun `insert_lines prepends when lineNumber is 1`() = runTest {
        val file = tempDir.newFile("prepend.txt")
        file.writeText("existing content")

        val result = toolManager.executeTool(
            name = "insert_lines",
            arguments = mapOf(
                "filePath" to file.absolutePath,
                "content" to "prepended line",
                "lineNumber" to "1"
            ),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertTrue(file.readText().startsWith("prepended line"))
    }

    // ── replace_lines tests ───────────────────────────────────────────────────

    @Test
    fun `replace_lines replaces specified range`() = runTest {
        val file = tempDir.newFile("replace.txt")
        file.writeText("aaa\nbbb\nccc\nddd")

        val result = toolManager.executeTool(
            name = "replace_lines",
            arguments = mapOf(
                "filePath" to file.absolutePath,
                "startLine" to "2",
                "endLine" to "3",
                "content" to "NEW_LINE_A\nNEW_LINE_B\nNEW_LINE_C"
            ),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        val lines = file.readText().lines()
        assertEquals(5, lines.size) // aaa + 3 new + ddd
        assertEquals("aaa", lines[0])
        assertEquals("NEW_LINE_A", lines[1])
        assertEquals("NEW_LINE_B", lines[2])
        assertEquals("NEW_LINE_C", lines[3])
        assertEquals("ddd", lines[4])
    }

    @Test
    fun `replace_lines returns error when endLine less than startLine`() = runTest {
        val file = tempDir.newFile("replace_err.txt")
        file.writeText("aaa\nbbb")

        val result = toolManager.executeTool(
            name = "replace_lines",
            arguments = mapOf(
                "filePath" to file.absolutePath,
                "startLine" to "3",
                "endLine" to "1",
                "content" to "x"
            ),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("endLine"))
    }

    // ── append_to_file tests ──────────────────────────────────────────────────

    @Test
    fun `append_to_file appends content with newline separator`() = runTest {
        val file = tempDir.newFile("append.txt")
        file.writeText("original content")

        val result = toolManager.executeTool(
            name = "append_to_file",
            arguments = mapOf("filePath" to file.absolutePath, "content" to "appended line"),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        val text = file.readText()
        assertTrue(text.contains("original content"))
        assertTrue(text.contains("appended line"))
        assertTrue(text.contains("\n"))
    }

    @Test
    fun `append_to_file without newline separator`() = runTest {
        val file = tempDir.newFile("append_no_nl.txt")
        file.writeText("hello")

        val result = toolManager.executeTool(
            name = "append_to_file",
            arguments = mapOf(
                "filePath" to file.absolutePath,
                "content" to " world",
                "addNewline" to "false"
            ),
            scopePath = scopePath
        )

        assertFalse(result.isError)
        assertEquals("hello world", file.readText())
    }

    @Test
    fun `append_to_file returns error for missing file`() = runTest {
        val result = toolManager.executeTool(
            name = "append_to_file",
            arguments = mapOf("filePath" to "$scopePath/missing.txt", "content" to "text"),
            scopePath = scopePath
        )

        assertTrue(result.isError)
        assertTrue(result.output.contains("File not found"))
    }
}
