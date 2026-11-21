package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.domain.history.ChatMetadataModel

internal object SpotifyPlayback {
    private val spotify = Regex("""(?i)\bspotify\b|سبوتيفاي""")
    private val track = Regex("""(?i)(?:\bplay\s+(?:the\s+song\s+)?(.+?)\s+by\s+(.+?)(?:\s+on\s+spotify|$)|(?:شغل|تشغل)\s+(?:[اأ]غني[هة]\s+)?(.+?)\s+ل(?:ـ)?([^\s].*?)(?:\s+(?:على|علي|في)\s+سبوتيفاي|$))""")
    private val session = Regex("""(?m)^\s*\d+\. (\S+)\s*$""")

    data class Track(val title: String, val artist: String)

    private val affirmativeContinuation = Regex(
        """(?i)(?:please\s+)?play it(?:\s+please)?[.!؟ ]*|(?:طب\s+)?(?:ما تتحكم انت في التلفون و\s*)?(?:شغلها|تشغلها)[.!؟ ]*"""
    )

    fun isPlaybackContinuation(goal: String): Boolean = affirmativeContinuation.matches(goal.trim())

    fun withTrackReference(goal: String, track: Track): String =
        "${goal.trim()}\nSpotify track: ${track.title}\nArtist: ${track.artist}"

    fun requested(goal: String): Track? {
        val lines = goal.lines()
        if (lines.size == 3 && isPlaybackContinuation(lines[0]) &&
            lines[1].startsWith("Spotify track: ") && lines[2].startsWith("Artist: ")) {
            val title = lines[1].removePrefix("Spotify track: ").trim()
            val artist = lines[2].removePrefix("Artist: ").trim()
            if (title.isNotBlank() && artist.isNotBlank()) return Track(title, artist)
        }
        if (!spotify.containsMatchIn(goal) || goal.trim().startsWith("how ", true)) return null
        val m = track.find(goal) ?: return null
        return if (m.groupValues[1].isNotBlank()) Track(m.groupValues[1].trim(), m.groupValues[2].trim())
            else Track(m.groupValues[3].trim(), m.groupValues[4].trim())
    }

    fun verified(track: Track, observation: String): Boolean {
        val headers = session.findAll(observation).toList()
        return headers.withIndex().any { (i, header) ->
            if (header.groupValues[1] != "com.spotify.music") return@any false
            val fields = observation.substring(header.range.last + 1, headers.getOrNull(i + 1)?.range?.first ?: observation.length)
                .lineSequence().map(String::trim).toList()
            fun matches(field: String, expected: String) = fields.any {
                it.startsWith(field) && ChatMetadataModel.normalize(it.removePrefix(field)) == ChatMetadataModel.normalize(expected)
            }
            matches("Title: ", track.title) && matches("Artist: ", track.artist) &&
                fields.any { it.startsWith("Status:") && it.endsWith("▶️ Playing") }
        }
    }
}
