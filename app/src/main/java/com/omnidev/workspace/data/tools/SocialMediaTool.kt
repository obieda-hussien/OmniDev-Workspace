package com.omnidev.workspace.data.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * SocialMediaTool — gives the AI agent the ability to fetch metadata, download, play,
 * and search videos from YouTube, TikTok, Instagram, Twitter/X, and 1000+ other platforms.
 *
 * Downloads are powered by **yt-dlp** executed via [PrivilegedExecutionManager]
 * (Shizuku → rish → root/SU).  Metadata is fetched from the free noembed.com oEmbed
 * proxy when yt-dlp is not installed.  Searches use the free Piped API (open YouTube
 * frontend) and fall back to yt-dlp's built-in `ytsearch:` extractor.
 *
 * ### Supported actions (`social_media_video` tool)
 * | Action          | Description                                                           |
 * |-----------------|-----------------------------------------------------------------------|
 * | `get_info`      | Title, author, description, thumbnail, platform, duration            |
 * | `get_captions`  | Subtitles / transcript (requires yt-dlp)                             |
 * | `download`      | Save video or audio to device storage (requires yt-dlp)              |
 * | `play`          | Open URL in YouTube / TikTok / browser via Android Intent            |
 * | `search_youtube`| Search YouTube; returns titles + URLs (API or yt-dlp search)         |
 * | `setup`         | Check yt-dlp & ffmpeg availability; print installation instructions  |
 */
object SocialMediaTool {

    private const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
    private const val DEFAULT_DOWNLOAD_DIR = "/sdcard/Download"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    // Platform detection patterns
    private val YOUTUBE_RE = Regex(
        """(?:https?://)?(?:www\.)?(?:youtube\.com/(?:watch\?.*v=|shorts/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
        RegexOption.IGNORE_CASE
    )
    private val TIKTOK_RE = Regex(
        """(?:https?://)?(?:vm\.tiktok\.com|www\.tiktok\.com)""",
        RegexOption.IGNORE_CASE
    )
    private val INSTAGRAM_RE = Regex(
        """(?:https?://)?(?:www\.)?instagram\.com/(?:p|reel|tv|stories)/""",
        RegexOption.IGNORE_CASE
    )
    private val TWITTER_RE = Regex(
        """(?:https?://)?(?:www\.)?(?:twitter\.com|x\.com)/\w+/status/""",
        RegexOption.IGNORE_CASE
    )

    // ─────────────────────────────────────────────────────────────────────
    // Tool definitions
    // ─────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "social_media_video",
            description = """
Interact with online videos from YouTube, TikTok, Instagram, Twitter/X, Vimeo, Twitch, and
1000+ other platforms supported by yt-dlp.

Actions and required parameters:
• get_info        — url: video URL. Returns title, channel, description, thumbnail, duration.
• get_captions    — url: video URL. Optional: lang (e.g. "en", "ar"). Returns subtitles as plain text (requires yt-dlp).
• download        — url: video URL. Optional: quality ("best"/"worst"/"audio_only", default "best"),
                    format ("mp4"/"mp3"/"mkv"/"webm", default "mp4"),
                    save_path (directory, default: /sdcard/Download).
• play            — url: video URL. Opens in YouTube app / TikTok app / browser.
• search_youtube  — query: search terms. Optional: max_results (1-20, default 5).
• setup           — Check yt-dlp and ffmpeg availability; print installation instructions.

Supported platforms include: YouTube, YouTube Shorts, TikTok, Instagram Reels/Stories,
Twitter/X, Facebook, Reddit, Twitch, Vimeo, Dailymotion, and 1000+ more via yt-dlp.
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action: get_info, get_captions, download, play, search_youtube, setup", required = true),
                ToolParameter("url", "string", "Video URL (required for get_info, get_captions, download, play)", required = false),
                ToolParameter("query", "string", "Search query for search_youtube", required = false),
                ToolParameter("quality", "string", "Download quality: best, worst, audio_only (default: best)", required = false),
                ToolParameter("format", "string", "Output format: mp4, mp3, mkv, webm (default: mp4)", required = false),
                ToolParameter("save_path", "string", "Download directory (default: /sdcard/Download)", required = false),
                ToolParameter("lang", "string", "Subtitle language code (default: en)", required = false),
                ToolParameter("max_results", "string", "Max search results 1-20 (default: 5)", required = false)
            )
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Execution router
    // ─────────────────────────────────────────────────────────────────────

    suspend fun execute(
        context: Context?,
        action: String,
        args: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (action.lowercase().trim()) {
            "get_info" -> {
                val url = args["url"] ?: return@withContext err("get_info requires 'url'")
                getVideoInfo(url)
            }

            "get_captions" -> {
                val url = args["url"] ?: return@withContext err("get_captions requires 'url'")
                getCaptions(url, args["lang"] ?: "en")
            }

            "download" -> {
                val url = args["url"] ?: return@withContext err("download requires 'url'")
                downloadVideo(
                    url = url,
                    quality = args["quality"] ?: "best",
                    format = args["format"] ?: "mp4",
                    savePath = args["save_path"]?.takeIf { it.isNotBlank() } ?: DEFAULT_DOWNLOAD_DIR
                )
            }

            "play" -> {
                val ctx = context
                    ?: return@withContext ToolExecutionResult(
                        "The 'play' action requires Android context to launch apps.",
                        isError = true
                    )
                val url = args["url"] ?: return@withContext err("play requires 'url'")
                playVideo(ctx, url)
            }

            "search_youtube" -> {
                val query = args["query"] ?: return@withContext err("search_youtube requires 'query'")
                val maxResults = args["max_results"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5
                searchYouTube(query, maxResults)
            }

            "setup" -> checkSetup()

            else -> err(
                "Unknown action '${action}'. Available: get_info, get_captions, download, play, search_youtube, setup"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Action implementations
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Fetch video metadata using the noembed.com oEmbed proxy (supports YouTube, TikTok, Vimeo,
     * etc. — no API key required) with an optional extended-info pass through yt-dlp.
     */
    private suspend fun getVideoInfo(url: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        val platform = detectPlatform(url)
        val sb = StringBuilder()
        sb.appendLine("🎬 Video Info")
        sb.appendLine("Platform : $platform")
        sb.appendLine("URL      : $url")
        sb.appendLine()

        // Primary: noembed.com — free oEmbed proxy for 100+ platforms
        val oembed = fetchOEmbed(url)
        if (oembed != null) {
            val title      = oembed.optString("title").takeIf { it.isNotBlank() }
            val author     = oembed.optString("author_name").takeIf { it.isNotBlank() }
            val thumbnail  = oembed.optString("thumbnail_url").takeIf { it.isNotBlank() }
            val provider   = oembed.optString("provider_name").takeIf { it.isNotBlank() }
            val width      = oembed.optInt("width").takeIf { it > 0 }
            val height     = oembed.optInt("height").takeIf { it > 0 }

            if (title     != null) sb.appendLine("Title    : $title")
            if (author    != null) sb.appendLine("Channel  : $author")
            if (provider  != null) sb.appendLine("Provider : $provider")
            if (thumbnail != null) sb.appendLine("Thumbnail: $thumbnail")
            if (width != null && height != null) sb.appendLine("Size     : ${width}x${height}")
        } else {
            sb.appendLine("⚠️  Could not fetch oEmbed metadata (platform may be unsupported or rate-limited).")
        }

        // Extended info via yt-dlp --print (selected fields only — avoids huge JSON payload)
        val ytdlp = findYtDlp()
        if (ytdlp != null) {
            sb.appendLine()
            sb.appendLine("── Extended info (yt-dlp) ──")
            // Use --print template so we only receive the fields we need (no large JSON blob)
            val cmd = "$ytdlp --no-warnings --skip-download " +
                "--print '%(description.50B)s|||%(duration_string)s|||%(view_count)s|||%(upload_date)s|||%(like_count)s|||%(channel)s|||%(formats.:.height)s' " +
                "${shellQuote(url)} 2>&1"
            val result = PrivilegedExecutionManager.executeCommand(cmd)
            val output = normalizeExecOutput(result.getOrNull())
            output?.lines()?.firstOrNull { it.contains("|||") }?.let { line ->
                val parts = line.split("|||")
                val desc     = parts.getOrNull(0)?.trim().takeIf { !it.isNullOrBlank() && it != "NA" }
                val duration = parts.getOrNull(1)?.trim().takeIf { !it.isNullOrBlank() && it != "NA" }
                val views    = parts.getOrNull(2)?.trim().takeIf { !it.isNullOrBlank() && it != "NA" && it != "0" }
                val date     = parts.getOrNull(3)?.trim().takeIf { it?.length == 8 }
                val likes    = parts.getOrNull(4)?.trim().takeIf { !it.isNullOrBlank() && it != "NA" && it != "0" }
                val channel  = parts.getOrNull(5)?.trim().takeIf { !it.isNullOrBlank() && it != "NA" }

                if (desc     != null) sb.appendLine("Description: ${desc.take(600)}${if (desc.length > 600) "…" else ""}")
                if (duration != null) sb.appendLine("Duration : $duration")
                if (views    != null) sb.appendLine("Views    : $views")
                if (date     != null) sb.appendLine("Uploaded : ${date.substring(0,4)}-${date.substring(4,6)}-${date.substring(6,8)}")
                if (likes    != null) sb.appendLine("Likes    : $likes")
                if (channel  != null && !sb.contains("Channel")) sb.appendLine("Channel  : $channel")
            }
            if (output.isNullOrBlank() || !output.contains("|||")) {
                val errMsg = result.exceptionOrNull()?.message?.lineSequence()?.firstOrNull()?.trim()
                if (!errMsg.isNullOrBlank()) {
                    sb.appendLine("⚠️  yt-dlp metadata fetch failed: $errMsg")
                }
            }
        } else if (oembed == null) {
            sb.appendLine()
            sb.appendLine("💡 Install yt-dlp for richer metadata: run action=setup")
        }

        ToolExecutionResult(sb.toString().trimEnd())
    }

    /**
     * Extract subtitles / auto-generated captions from a video using yt-dlp.
     * Downloads to a temporary directory in /data/local/tmp (shell-writable) and
     * reads the file back as plain text.
     */
    private suspend fun getCaptions(url: String, lang: String): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val ytdlp = findYtDlp()
                ?: return@withContext err(
                    "❌ yt-dlp not found. Run action=setup for installation instructions."
                )

            val safeLang = lang.replace(Regex("[^a-zA-Z\\-]"), "").ifBlank { "en" }
            val tmpDir = "/data/local/tmp/omnidev_subs_${System.currentTimeMillis()}"
            val quotedTmpDir = shellQuote(tmpDir)
            val sentinel = "__OMNIDEV_NO_CAPTIONS_8f3a2b__"

            val cmd = buildString {
                append("mkdir -p $quotedTmpDir && ")
                append("$ytdlp --no-warnings --skip-download ")
                append("--write-subs --write-auto-subs ")
                append("--sub-lang ${shellQuote(safeLang)} ")
                append("--sub-format 'srt/vtt/best' ")
                append("-o ${shellQuote("$tmpDir/sub")} ")
                append("${shellQuote(url)} 2>&1; ")
                // Find the downloaded subtitle file and cat it
                append("SUB=\$(ls $quotedTmpDir/*.srt $quotedTmpDir/*.vtt 2>/dev/null | head -1); ")
                append("if [ -n \"\$SUB\" ]; then cat \"\$SUB\"; else echo '$sentinel'; fi; ")
                append("rm -rf $quotedTmpDir")
            }

            val result = PrivilegedExecutionManager.executeCommand(cmd)
            val output = result.getOrNull()
                ?: return@withContext err("❌ Failed to run yt-dlp: ${result.exceptionOrNull()?.message}")

            if (output.contains(sentinel)) {
                return@withContext ToolExecutionResult(
                    "No captions found for language '$safeLang'.\n" +
                    "Try a different language (e.g. 'en', 'ar', 'fr') or check if the video has subtitles enabled."
                )
            }

            val plain = subtitleToPlainText(output)
            if (plain.isBlank()) {
                return@withContext ToolExecutionResult(
                    "Captions were found but appeared to be empty after parsing.\nRaw output (first 500 chars):\n${output.take(500)}"
                )
            }

            ToolExecutionResult("📝 Captions [$safeLang]:\n\n$plain")
        }

    /**
     * Download video or audio using yt-dlp, saving to [savePath] on the device.
     */
    private suspend fun downloadVideo(
        url: String,
        quality: String,
        format: String,
        savePath: String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val ytdlp = findYtDlp()
            ?: return@withContext err(
                "❌ yt-dlp not found. Run action=setup for installation instructions."
            )

        val safeFormat = format.replace(Regex("[^a-zA-Z0-9]"), "").ifBlank { "mp4" }
        val outputTemplate = "${savePath}/%(title)s.%(ext)s"

        // Build format selector
        val formatSelector = when (quality.lowercase()) {
            "audio_only", "audio" -> "bestaudio/best"
            "worst"               -> "worst"
            else                  -> "bestvideo+bestaudio/best"
        }

        val cmd = buildString {
            append("mkdir -p ${shellQuote(savePath)} && ")
            append("$ytdlp --no-warnings ")
            append("-f ${shellQuote(formatSelector)} ")
            if (quality.lowercase() !in setOf("audio_only", "audio")) {
                append("--merge-output-format $safeFormat ")
            } else {
                append("--extract-audio --audio-format mp3 ")
            }
            append("-o ${shellQuote(outputTemplate)} ")
            append("--print after_move:filepath ")
            append("${shellQuote(url)} 2>&1")
        }

        val result = PrivilegedExecutionManager.executeCommand(cmd)
        result.fold(
            onSuccess = { output ->
                // The last line printed by --print after_move:filepath is the final path
                val savedFile = output.lines()
                    .lastOrNull { it.contains("/") && !it.trimStart().startsWith("[") }
                    ?: savePath
                ToolExecutionResult(
                    "✅ Download complete!\nSaved to: $savedFile\n\nOutput:\n${output.take(1000)}"
                )
            },
            onFailure = { e ->
                err("❌ Download failed: ${e.message}")
            }
        )
    }

    /**
     * Open the video URL using an Android Intent so the OS routes it to the right app
     * (YouTube, TikTok, browser, etc.).
     */
    private fun playVideo(context: Context, url: String): ToolExecutionResult {
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val platform = detectPlatform(url)
            ToolExecutionResult("✅ Opening $platform video in the appropriate app…")
        }.getOrElse { e ->
            err("❌ Could not open video: ${e.message}")
        }
    }

    /**
     * Search YouTube for videos.  Attempts yt-dlp's built-in `ytsearch:` extractor first
     * (no API key needed), then falls back to the free Piped API.
     */
    private suspend fun searchYouTube(query: String, maxResults: Int): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val ytdlp = findYtDlp()
            if (ytdlp != null) {
                // --dump-json with --flat-playlist prints one JSON object per line
                val cmd = "$ytdlp --no-warnings --flat-playlist --dump-json " +
                          "ytsearch${maxResults}:${shellQuote(query)} 2>&1"
                val result = PrivilegedExecutionManager.executeCommand(cmd)
                val output = result.getOrNull()
                if (!output.isNullOrBlank() && !output.trimStart().startsWith("ERROR")) {
                    val sb = StringBuilder("🔍 YouTube Search: \"$query\"\n\n")
                    var count = 0
                    for (line in output.lines()) {
                        if (!line.trimStart().startsWith("{")) continue
                        runCatching {
                            val j        = JSONObject(line)
                            val title    = j.optString("title").takeIf { it.isNotBlank() } ?: return@runCatching
                            val videoUrl = j.optString("webpage_url").takeIf { it.isNotBlank() }
                                ?: j.optString("url").takeIf { it.isNotBlank() } ?: return@runCatching
                            val duration = j.optString("duration_string").takeIf { it.isNotBlank() }
                            val channel  = j.optString("uploader").takeIf { it.isNotBlank() }
                            count++
                            sb.appendLine("$count. $title")
                            if (channel  != null) sb.appendLine("   Channel : $channel")
                            if (duration != null) sb.appendLine("   Duration: $duration")
                            sb.appendLine("   URL     : $videoUrl")
                            sb.appendLine()
                        }
                    }
                    if (count > 0) return@withContext ToolExecutionResult(sb.toString().trimEnd())
                }
            }

            // Fallback: Piped API (open-source YouTube frontend, no auth required)
            searchYouTubePiped(query, maxResults)
        }

    /**
     * Search YouTube via the Piped public API — open-source, no API key required.
     */
    private suspend fun searchYouTubePiped(query: String, maxResults: Int): ToolExecutionResult {
        return runCatching {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val apiUrl = "https://pipedapi.kavin.rocks/search?q=$encodedQuery&filter=videos"
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout    = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "OmniDev/1.0")
            conn.setRequestProperty("Accept", "application/json")

            if (conn.responseCode != 200) {
                return ToolExecutionResult(
                    "❌ Search API returned HTTP ${conn.responseCode}.\n" +
                    "Install yt-dlp for offline search: run action=setup",
                    isError = true
                )
            }

            val response = conn.inputStream.bufferedReader().readText()
            val items = JSONObject(response).optJSONArray("items")
                ?: return ToolExecutionResult("No search results found.")

            val sb = StringBuilder("🔍 YouTube Search: \"$query\"\n\n")
            val limit = minOf(items.length(), maxResults)
            for (i in 0 until limit) {
                val item     = items.getJSONObject(i)
                val title    = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                val videoUrl = "https://www.youtube.com${item.optString("url")}"
                val secs     = item.optLong("duration")
                val duration = if (secs > 0) "%d:%02d".format(secs / 60, secs % 60) else null
                val channel  = item.optString("uploaderName").takeIf { it.isNotBlank() }
                val views    = item.optLong("views").takeIf { it > 0 }

                sb.appendLine("${i + 1}. $title")
                if (channel  != null) sb.appendLine("   Channel : $channel")
                if (duration != null) sb.appendLine("   Duration: $duration")
                if (views    != null) sb.appendLine("   Views   : $views")
                sb.appendLine("   URL     : $videoUrl")
                sb.appendLine()
            }
            ToolExecutionResult(sb.toString().trimEnd())
        }.getOrElse { e ->
            ToolExecutionResult(
                "❌ Search failed: ${e.message}\nInstall yt-dlp for local search: run action=setup",
                isError = true
            )
        }
    }

    /**
     * Check yt-dlp and ffmpeg availability, and print step-by-step installation instructions.
     */
    private suspend fun checkSetup(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder("🔧 Social Media Video Tool — Setup\n\n")

        val ytdlp = findYtDlp()
        if (ytdlp != null) {
            val version = PrivilegedExecutionManager.executeCommand("$ytdlp --version 2>&1")
                .getOrNull()
                .takeIf { !it.isNullOrBlank() && it != "(no output)" }
                ?.trim()
                ?: "unknown"
            sb.appendLine("✅ yt-dlp: $ytdlp (v$version)")
            sb.appendLine()
            sb.appendLine("All actions available:")
            sb.appendLine("  • get_info      — Video metadata")
            sb.appendLine("  • get_captions  — Subtitles / transcript")
            sb.appendLine("  • download      — Save to device storage")
            sb.appendLine("  • play          — Open in app")
            sb.appendLine("  • search_youtube— Search YouTube")
        } else {
            sb.appendLine("❌ yt-dlp not found.")
            sb.appendLine()
            sb.appendLine("Install via one of these methods:")
            sb.appendLine()
            sb.appendLine("1️⃣  Termux (recommended on Android):")
            sb.appendLine("   Use agent_runtime tool: action=termux_pkg_install packages='yt-dlp'")
            sb.appendLine()
            sb.appendLine("2️⃣  pip (if Python is installed):")
            sb.appendLine("   Use agent_runtime tool: action=pip_install packages='yt-dlp'")
            sb.appendLine()
            sb.appendLine("3️⃣  Direct binary (via privileged shell):")
            sb.appendLine("   Use agent_runtime tool: action=download_exec")
            sb.appendLine("   url='https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp'")
            sb.appendLine()
            sb.appendLine("Note: get_info and search_youtube work without yt-dlp (via web APIs).")
            sb.appendLine("      download and get_captions require yt-dlp.")
        }

        // Check ffmpeg (needed for best-quality merging)
        sb.appendLine()
        val ffmpegResult = PrivilegedExecutionManager.executeCommand("which ffmpeg 2>/dev/null")
        val ffmpegSys = ffmpegResult.getOrNull()?.trim().takeIf { !it.isNullOrBlank() }
        val ffmpegTermux = if (ffmpegSys == null) {
            val check = PrivilegedExecutionManager.executeCommand(
                "test -x $TERMUX_BIN/ffmpeg && echo $TERMUX_BIN/ffmpeg"
            ).getOrNull()?.trim()
            check.takeIf { !it.isNullOrBlank() }
        } else null

        val ffmpegPath = ffmpegSys ?: ffmpegTermux
        if (ffmpegPath != null) {
            sb.appendLine("✅ ffmpeg: $ffmpegPath (best-quality video merging available)")
        } else {
            sb.appendLine("⚠️  ffmpeg not found — high-quality downloads may be limited.")
            sb.appendLine("   Install: use agent_runtime action=termux_pkg_install packages='ffmpeg'")
        }

        ToolExecutionResult(sb.toString().trimEnd())
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Locate yt-dlp binary, checking:
     *  1. System PATH (`which yt-dlp`)
     *  2. Termux bin directory
     *  3. Python module invocation (`python3 -m yt_dlp`)
     *  4. Termux Python module
     */
    private suspend fun findYtDlp(): String? {
        // 1. System PATH
        val sysBin = PrivilegedExecutionManager.executeCommand("which yt-dlp 2>/dev/null")
            .getOrNull()
            .let(::normalizeExecOutput)
            ?.takeIf { it.startsWith("/") && (it.endsWith("/yt-dlp") || it.endsWith("/yt-dlp.exe")) }
        if (sysBin != null) return sysBin

        // 2. Termux bin
        val termuxBin = "$TERMUX_BIN/yt-dlp"
        val termuxExists = PrivilegedExecutionManager.executeCommand(
            "test -x $termuxBin && echo yes 2>/dev/null"
        ).getOrNull()?.trim() == "yes"
        if (termuxExists) return termuxBin

        // 3. Python module (system python3)
        val pipCheck = PrivilegedExecutionManager.executeCommand(
            "python3 -m yt_dlp --version 2>/dev/null"
        ).getOrNull().let(::normalizeExecOutput)
        if (!pipCheck.isNullOrBlank() && pipCheck.isNotEmpty() && pipCheck.first().isDigit()) return "python3 -m yt_dlp"

        // 4. Termux Python module
        val termuxPipCheck = PrivilegedExecutionManager.executeCommand(
            "$TERMUX_BIN/python3 -m yt_dlp --version 2>/dev/null"
        ).getOrNull().let(::normalizeExecOutput)
        if (!termuxPipCheck.isNullOrBlank() && termuxPipCheck.isNotEmpty() && termuxPipCheck.first().isDigit()) {
            return "$TERMUX_BIN/python3 -m yt_dlp"
        }

        return null
    }

    /**
     * Determine which social platform [url] belongs to.
     */
    private fun detectPlatform(url: String): String = when {
        YOUTUBE_RE.containsMatchIn(url) || "youtube.com" in url || "youtu.be" in url -> "YouTube"
        TIKTOK_RE.containsMatchIn(url) || "tiktok.com" in url -> "TikTok"
        INSTAGRAM_RE.containsMatchIn(url) || "instagram.com" in url -> "Instagram"
        TWITTER_RE.containsMatchIn(url) || "twitter.com" in url || "x.com" in url -> "Twitter/X"
        "vimeo.com" in url -> "Vimeo"
        "twitch.tv" in url -> "Twitch"
        "facebook.com" in url || "fb.watch" in url -> "Facebook"
        "reddit.com" in url || "redd.it" in url -> "Reddit"
        "dailymotion.com" in url -> "Dailymotion"
        else -> "Video"
    }

    /**
     * Fetch oEmbed metadata for [url] via noembed.com, which proxies oEmbed for 100+ platforms
     * (YouTube, TikTok, Vimeo, Twitter, etc.) without requiring any API key.
     */
    private fun fetchOEmbed(url: String): JSONObject? {
        return runCatching {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            val conn = URL("https://noembed.com/embed?url=$encoded").openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout    = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "OmniDev/1.0")
            if (conn.responseCode == 200) {
                val j = JSONObject(conn.inputStream.bufferedReader().readText())
                if (j.optString("error").isNotBlank()) null else j
            } else null
        }.getOrNull()
    }

    /**
     * Convert SRT or WebVTT subtitle text to plain readable text, stripping timestamps,
     * sequence numbers, cue identifiers, and HTML/VTT markup tags.
     */
    private fun subtitleToPlainText(raw: String): String {
        val timestampSrt = Regex("""^\d{2}:\d{2}:\d{2}[,\.]\d{3}\s*-->\s*""")
        val tagRegex     = Regex("""<[^>]+>""")
        val sb = StringBuilder()
        var prev = ""
        for (line in raw.lines()) {
            val s = line.trim()
            when {
                s.startsWith("WEBVTT")                 -> continue
                s.startsWith("NOTE")                   -> continue
                s.matches(Regex("""^\d+$"""))          -> continue  // cue/sequence number
                timestampSrt.containsMatchIn(s)        -> continue  // timestamp line
                s.isBlank() -> {
                    if (prev.isNotBlank()) {
                        sb.appendLine()
                        prev = ""
                    }
                }
                else -> {
                    val text = tagRegex.replace(s, "").trim()
                    if (text.isNotBlank() && text != prev) {
                        sb.appendLine(text)
                        prev = text
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    /**
     * Normalizes command output where some backends may return "(no output)" placeholder
     * for successful commands with empty stdout.
     */
    private fun normalizeExecOutput(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value == "(no output)") return null
        if (value.startsWith("ERROR", ignoreCase = true)) return null
        return value
    }

    /** POSIX single-quote escaping for safe shell argument interpolation. */
    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun err(msg: String) = ToolExecutionResult(msg, isError = true)
}
