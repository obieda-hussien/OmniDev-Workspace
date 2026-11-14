package com.omnidev.workspace.data.repository

import com.omnidev.workspace.domain.history.HistoryUserInputReader
import java.io.Reader
import java.io.StringReader

/** Prefer original composer input; legacy envelopes are read in bounded SQLite pages. */
internal object HistoryMetadataText {
    fun original(content: String, metadata: String): String = extract(content, StringReader(metadata))

    fun original(content: String, page: (Int) -> String): String {
        val reader = object : Reader() {
            private var chunk = ""
            private var index = 0
            private var databaseOffset = 1
            private var ended = false
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
                if (length == 0) return 0
                if (index == chunk.length) {
                    if (ended) return -1
                    chunk = page(databaseOffset)
                    index = 0
                    if (chunk.isEmpty()) { ended = true; return -1 }
                    // SQLite substr positions count code points, while Reader offsets count UTF-16 units.
                    databaseOffset += chunk.codePointCount(0, chunk.length)
                }
                val count = minOf(length, chunk.length - index)
                for (i in 0 until count) buffer[offset + i] = chunk[index + i]
                index += count
                return count
            }
            override fun close() { ended = true }
        }
        return extract(content, reader)
    }

    private fun extract(content: String, reader: Reader): String {
        val input = reader.use { HistoryUserInputReader.readPrefix(it) }
        return bounded(input?.takeIf { it.isNotBlank() } ?: content)
    }

    private fun bounded(value: String): String {
        if (value.length <= 1500) return value
        val end = if (Character.isHighSurrogate(value[1499])) 1499 else 1500
        return value.substring(0, end)
    }
}
