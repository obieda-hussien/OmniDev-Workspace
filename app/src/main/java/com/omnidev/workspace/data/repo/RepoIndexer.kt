package com.omnidev.workspace.data.repo

import android.util.Log
import com.omnidev.workspace.data.db.dao.RepoIndexDao
import com.omnidev.workspace.data.db.entities.RepoFileIndexEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.security.MessageDigest

/** RepoIndexer incrementally indexes files when mtime or size changes. Processes chunks of 50 with yield between chunks, skips binary files, files above 500 KB and excluded directories, and limits stored symbols to 5000 per scope with LRU eviction. */
class RepoIndexer(
    private val dao: RepoIndexDao,
    /** Maximum file size to read; larger files are skipped. */
    private val maxFileSizeBytes: Long = 500L * 1024,
    /** Maximum stored symbols per scope. */
    private val maxSymbolsPerScope: Int = 5000,
    /** Chunk size for memory-constrained devices (50 files per chunk). */
    private val chunkSize: Int = 50,
    /** Delay between chunks in milliseconds to reduce CPU and IO pressure. */
    private val chunkDelayMs: Long = 25
) {

    companion object {
        private const val TAG = "RepoIndexer"

        /** Globally excluded directories. */
        private val IGNORED_DIRS = setOf(
            ".git", "node_modules", "build", ".gradle", ".idea",
            "dist", "out", "target", ".next", ".cache",
            "vendor", "__pycache__", ".venv", "venv", ".dart_tool",
            "Pods", "DerivedData"
        )

        /** File extensions excluded from indexing. */
        private val IGNORED_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "svg", "webp",
            "mp3", "mp4", "mov", "wav", "flac", "ogg", "webm",
            "zip", "tar", "gz", "7z", "rar", "jar", "war", "apk",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "ttf", "otf", "woff", "woff2",
            "so", "dll", "dylib", "exe", "class", "dex",
            "lock", "sum"
        )
    }

    data class IndexProgress(
        val totalScanned: Int,
        val indexed: Int,
        val skipped: Int,
        val updated: Int,
        val unchanged: Int,
        val symbolsExtracted: Int,
        val elapsedMs: Long
    )

    // ──────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────

    /** Incrementally index an entire scope; onProgress is an optional callback invoked after each chunk. */
    suspend fun indexScope(
        scopePath: String,
        onProgress: ((IndexProgress) -> Unit)? = null
    ): IndexProgress = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val root = File(scopePath)
        if (!root.exists() || !root.isDirectory) {
            return@withContext IndexProgress(0, 0, 0, 0, 0, 0, 0)
        }

        // 1) Collect files with a lazy walk.
        val files = collectFiles(root)
        Log.d(TAG, "📁 Scanning ${files.size} files in $scopePath")

        var indexed = 0
        var skipped = 0
        var updated = 0
        var unchanged = 0
        var symbols = 0

        // 2) Process in chunks.
        for ((cidx, chunk) in files.chunked(chunkSize).withIndex()) {
            for (file in chunk) {
                try {
                    val res = processFile(scopePath, file)
                    when (res) {
                        is FileResult.Skipped -> skipped++
                        is FileResult.Unchanged -> unchanged++
                        is FileResult.Updated -> { updated++; symbols += res.symbolsCount }
                        is FileResult.Indexed -> { indexed++; symbols += res.symbolsCount }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "skip ${file.path}: ${t.message}")
                    skipped++
                }
            }
            // Yield between chunks to keep the UI responsive.
            yield()
            if (chunkDelayMs > 0) delay(chunkDelayMs)

            onProgress?.invoke(
                IndexProgress(
                    totalScanned = files.size,
                    indexed = indexed,
                    skipped = skipped,
                    updated = updated,
                    unchanged = unchanged,
                    symbolsExtracted = symbols,
                    elapsedMs = System.currentTimeMillis() - start
                )
            )
        }

        // 3) Enforce the scope's symbol quota.
        enforceSymbolQuota(scopePath)

        IndexProgress(
            totalScanned = files.size,
            indexed = indexed,
            skipped = skipped,
            updated = updated,
            unchanged = unchanged,
            symbolsExtracted = symbols,
            elapsedMs = System.currentTimeMillis() - start
        )
    }

    /** Update one file, for example after a save. */
    suspend fun reindexFile(scopePath: String, filePath: String) =
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    dao.deleteFile(scopePath, filePath)
                    dao.deleteSymbolsForFile(scopePath, filePath)
                    return@withContext
                }
                processFile(scopePath, file)
            } catch (t: Throwable) {
                Log.w(TAG, "reindexFile failed: ${t.message}")
            }
        }

    /** Delete all indexed data for a scope when switching projects. */
    suspend fun clearScope(scopePath: String) = withContext(Dispatchers.IO) {
        dao.clearScope(scopePath)
        dao.clearSymbolsForScope(scopePath)
    }

    // ──────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────

    private sealed interface FileResult {
        object Skipped : FileResult
        object Unchanged : FileResult
        data class Updated(val symbolsCount: Int) : FileResult
        data class Indexed(val symbolsCount: Int) : FileResult
    }

    private suspend fun processFile(scopePath: String, file: File): FileResult {
        val relativePath = file.absolutePath
        val size = file.length()
        val mtime = file.lastModified()

        // skip large/binary
        if (size > maxFileSizeBytes) return FileResult.Skipped
        val ext = file.extension.lowercase()
        if (ext in IGNORED_EXTENSIONS) return FileResult.Skipped

        val existing = dao.getFile(scopePath, relativePath)
        // Incremental indexing: skip unchanged mtime and size without reading.
        if (existing != null && existing.fileMtime == mtime && existing.fileSize == size) {
            return FileResult.Unchanged
        }

        // Read content.
        val content = try {
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            // Skip possible binary content.
            return FileResult.Skipped
        }

        val language = RepoSymbolExtractor.detectLanguage(relativePath)
        val hash = sha256Prefix(content.toByteArray())

        val symbols = if (RepoSymbolExtractor.shouldExtract(language)) {
            RepoSymbolExtractor.extract(scopePath, relativePath, content, language, mtime)
        } else emptyList()

        // upsert file index
        dao.upsertFile(
            RepoFileIndexEntry(
                id = existing?.id ?: 0,
                scopePath = scopePath,
                filePath = relativePath,
                fileSize = size,
                fileMtime = mtime,
                contentHash = hash,
                symbolCount = symbols.size,
                language = language,
                indexedAt = System.currentTimeMillis()
            )
        )
        // Replace old file symbols with newly extracted symbols.
        dao.deleteSymbolsForFile(scopePath, relativePath)
        if (symbols.isNotEmpty()) dao.insertSymbols(symbols)

        return if (existing == null) FileResult.Indexed(symbols.size)
        else FileResult.Updated(symbols.size)
    }

    /** Walk recursively, pruning IGNORED_DIRS early. */
    private fun collectFiles(root: File): List<File> {
        val out = ArrayList<File>(1024)
        val stack = ArrayDeque<File>()
        val canonicalRoot = root.canonicalFile.path.trimEnd(File.separatorChar) + File.separator
        val visitedDirectories = hashSetOf(root.canonicalPath)
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (c in children) {
                // Do not index source reached through links outside the selected project.
                val canonical = runCatching { c.canonicalPath }.getOrNull() ?: continue
                if (!canonical.startsWith(canonicalRoot)) continue
                if (c.isDirectory) {
                    if (c.name !in IGNORED_DIRS && !c.name.startsWith(".") &&
                        visitedDirectories.add(canonical)) {
                        stack.addLast(c)
                    }
                } else if (c.isFile) {
                    out += c
                }
            }
        }
        return out
    }

    private fun sha256Prefix(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest(bytes)
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    private suspend fun enforceSymbolQuota(scopePath: String) {
        try {
            val cnt = dao.countSymbols(scopePath)
            if (cnt > maxSymbolsPerScope) {
                val toEvict = (cnt - maxSymbolsPerScope).coerceAtLeast(200)
                dao.evictOldestSymbols(scopePath, toEvict)
                Log.d(TAG, "🧹 evicted $toEvict symbols (cnt=$cnt) in $scopePath")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enforceSymbolQuota failed: ${t.message}")
        }
    }
}
