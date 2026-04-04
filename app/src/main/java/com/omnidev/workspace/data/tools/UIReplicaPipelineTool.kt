package com.omnidev.workspace.data.tools

import android.content.Context
import com.omnidev.workspace.data.accessibility.AccessibilityStateManager
import com.omnidev.workspace.data.accessibility.SemanticUITool
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Unified pipeline for "screen-to-code" replication tasks:
 * 1) (Optional) launch target app
 * 2) capture screenshot + semantic tree (+ Shizuku XML fallback)
 * 3) validate generated code similarity against captured UI signals
 */
object UIReplicaPipelineTool {

    private const val MAX_RAW_DUMP_CHARS = 40_000
    private const val DEFAULT_WAIT_MS = 1_500L
    private const val DEFAULT_EMPTY_SIGNAL_SCORE = 0.60
    private const val TEXT_SCORE_WEIGHT = 0.65
    private const val STRUCTURE_SCORE_WEIGHT = 0.35
    private const val HIGH_SIMILARITY_THRESHOLD = 85
    private const val MEDIUM_SIMILARITY_THRESHOLD = 70
    private const val TARGET_SIMILARITY_THRESHOLD = HIGH_SIMILARITY_THRESHOLD

    data class CaptureBundle(
        val packageName: String?,
        val targetLanguage: String?,
        val semanticTree: String?,
        val rawDumpXml: String?,
        val screenshotDataUri: String?,
        val capturedAtMs: Long
    )

    @Volatile
    private var lastCapture: CaptureBundle? = null

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "ui_replica_pipeline",
            description = "Integrated screen-to-code pipeline. " +
                "Use action=capture_reference to launch app + capture screenshot + UI dump " +
                "(Accessibility first, Shizuku XML fallback). " +
                "Use action=validate_code to score generated code similarity and get refinement guidance. " +
                "Use action=orchestrate_replica to run capture+validation together in one call.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "One of: capture_reference, validate_code, orchestrate_replica",
                    required = true
                ),
                ToolParameter(
                    name = "package_name",
                    type = "string",
                    description = "Android package to launch before capture (e.g. com.google.android.youtube).",
                    required = false
                ),
                ToolParameter(
                    name = "target_language",
                    type = "string",
                    description = "Requested output language/framework (compose/xml/java/html/css/js/react/etc).",
                    required = false
                ),
                ToolParameter(
                    name = "wait_ms",
                    type = "string",
                    description = "Delay after launching app before capture (300..8000). Default 1500.",
                    required = false
                ),
                ToolParameter(
                    name = "generated_code",
                    type = "string",
                    description = "Generated code to validate against captured UI.",
                    required = false
                ),
                ToolParameter(
                    name = "semantic_tree",
                    type = "string",
                    description = "Optional semantic tree override for validation (uses last captured if omitted).",
                    required = false
                ),
                ToolParameter(
                    name = "force_capture",
                    type = "string",
                    description = "For action=orchestrate_replica. true/false. If true, refresh capture before validation.",
                    required = false
                )
            )
        )
    )

    suspend fun execute(
        context: Context?,
        action: String,
        args: Map<String, String>
    ): ToolExecutionResult {
        return when (action.lowercase(Locale.ROOT)) {
            "capture_reference" -> {
                if (context == null) {
                    ToolExecutionResult("capture_reference requires Android context.", isError = true)
                } else {
                    captureReference(context, args)
                }
            }
            "validate_code" -> validateCode(args)
            "orchestrate_replica" -> orchestrateReplica(context, args)
            else -> ToolExecutionResult(
                "Unknown action '$action'. Valid actions: capture_reference, validate_code, orchestrate_replica.",
                isError = true
            )
        }
    }

    private suspend fun orchestrateReplica(
        context: Context?,
        args: Map<String, String>
    ): ToolExecutionResult {
        val generatedCode = args["generated_code"]?.trim()
        val shouldForceCapture = args["force_capture"]?.trim()?.equals("true", ignoreCase = true) == true
        val needsCapture = shouldForceCapture || lastCapture == null

        val captureResult = if (needsCapture) {
            val ctx = context ?: return ToolExecutionResult(
                "orchestrate_replica requires Android context when capture is needed.",
                isError = true
            )
            captureReference(ctx, args)
        } else {
            null
        }

        if (generatedCode.isNullOrBlank()) {
            return captureResult ?: ToolExecutionResult(
                "orchestrate_replica finished. Capture is already available. Pass generated_code for validation."
            )
        }

        val validationResult = validateCode(args)
        val mergedOutput = buildString {
            appendLine("UI replica orchestration completed.")
            captureResult?.let {
                appendLine()
                appendLine("Capture phase:")
                appendLine(it.output)
            }
            appendLine()
            appendLine("Validation phase:")
            appendLine(validationResult.output)
        }

        return ToolExecutionResult(
            output = mergedOutput.trim(),
            isError = (captureResult?.isError == true) || validationResult.isError
        )
    }

    private suspend fun captureReference(context: Context, args: Map<String, String>): ToolExecutionResult {
        val packageName = args["package_name"]?.trim().orEmpty().ifBlank { null }
        val targetLanguage = args["target_language"]?.trim().orEmpty().ifBlank { null }
        val waitMs = (args["wait_ms"]?.toLongOrNull() ?: DEFAULT_WAIT_MS).coerceIn(300L, 8_000L)

        val launchStatus = if (packageName != null) launchPackage(context, packageName) else "Skipped app launch."
        delay(waitMs)

        val semantic = SemanticUITool.execute("dump_tree", emptyMap())
        val semanticTree = semantic.output.takeIf { !semantic.isError }
        val rawDump = captureRawDumpViaShizuku()
        val screenshot = VisualInspectorTool.execute(context)
        val screenshotDataUri = extractScreenshotDataUri(screenshot.output)

        val bundle = CaptureBundle(
            packageName = packageName ?: AccessibilityStateManager.activePackage.value,
            targetLanguage = targetLanguage,
            semanticTree = semanticTree,
            rawDumpXml = rawDump,
            screenshotDataUri = screenshotDataUri,
            capturedAtMs = System.currentTimeMillis()
        )
        lastCapture = bundle

        if (bundle.semanticTree.isNullOrBlank() && bundle.rawDumpXml.isNullOrBlank() && bundle.screenshotDataUri.isNullOrBlank()) {
            return ToolExecutionResult(
                "Capture failed: no semantic tree, no XML dump, and no screenshot were produced.",
                isError = true
            )
        }

        val output = buildString {
            appendLine("UI replica reference captured.")
            appendLine("Launch: $launchStatus")
            appendLine("Package: ${bundle.packageName ?: "unknown"}")
            appendLine("Target language: ${bundle.targetLanguage ?: "not specified"}")
            appendLine("Semantic tree: ${if (bundle.semanticTree != null) "available" else "unavailable (${semantic.output})"}")
            appendLine("Raw UI dump (Shizuku): ${if (bundle.rawDumpXml != null) "available" else "unavailable"}")
            appendLine("Screenshot: ${if (bundle.screenshotDataUri != null) "available" else "unavailable (${screenshot.output})"}")
            appendLine()
            appendLine("[UI_REPLICA_REFERENCE]")
            bundle.semanticTree?.let {
                appendLine("[SEMANTIC_TREE]")
                appendLine(it)
                appendLine("[/SEMANTIC_TREE]")
            }
            bundle.rawDumpXml?.let {
                appendLine("[RAW_UI_DUMP_XML]")
                appendLine(it)
                appendLine("[/RAW_UI_DUMP_XML]")
            }
            bundle.screenshotDataUri?.let {
                appendLine("[SCREENSHOT_BASE64]")
                appendLine(it)
                appendLine("[/SCREENSHOT_BASE64]")
            }
            appendLine("[/UI_REPLICA_REFERENCE]")
        }

        return ToolExecutionResult(output = output)
    }

    private fun validateCode(args: Map<String, String>): ToolExecutionResult {
        val generatedCode = args["generated_code"]?.trim()
            ?: return ToolExecutionResult("validate_code requires 'generated_code'.", isError = true)
        if (generatedCode.isBlank()) {
            return ToolExecutionResult("generated_code is empty.", isError = true)
        }

        val capture = lastCapture
        val semanticSource = args["semantic_tree"]?.takeIf { it.isNotBlank() }
            ?: capture?.semanticTree
            ?: capture?.rawDumpXml
            ?: return ToolExecutionResult(
                "No UI reference available for validation. Run capture_reference first (or provide semantic_tree).",
                isError = true
            )

        val targetLanguage = args["target_language"]?.trim().orEmpty().ifBlank {
            capture?.targetLanguage ?: "generic"
        }

        val expectedTexts = extractExpectedTexts(semanticSource)
        val matchedTexts = expectedTexts.filter { generatedCode.contains(it, ignoreCase = true) }

        val textScore = if (expectedTexts.isEmpty()) {
            DEFAULT_EMPTY_SIGNAL_SCORE
        } else {
            matchedTexts.size.toDouble() / expectedTexts.size.toDouble()
        }

        val expectedComponents = extractComponentHints(semanticSource)
        val expectedKeywords = expectedComponents
            .flatMap { componentKeywordsForLanguage(it, targetLanguage) }
            .distinct()
        val matchedKeywords = expectedKeywords.filter { generatedCode.contains(it, ignoreCase = true) }
        val structureScore = if (expectedKeywords.isEmpty()) {
            DEFAULT_EMPTY_SIGNAL_SCORE
        } else {
            matchedKeywords.size.toDouble() / expectedKeywords.size.toDouble()
        }

        val totalScore = (((textScore * TEXT_SCORE_WEIGHT) + (structureScore * STRUCTURE_SCORE_WEIGHT)) * 100.0).roundToInt()
        val similarityLabel = when {
            totalScore >= HIGH_SIMILARITY_THRESHOLD -> "High similarity"
            totalScore >= MEDIUM_SIMILARITY_THRESHOLD -> "Medium similarity"
            else -> "Low similarity"
        }

        val missingTexts = expectedTexts.filterNot { matchedTexts.contains(it) }.take(8)
        val missingKeywords = expectedKeywords.filterNot { matchedKeywords.contains(it) }.take(8)

        val recommendations = mutableListOf<String>()
        if (missingTexts.isNotEmpty()) {
            recommendations += "Add/align visible labels from UI: ${missingTexts.joinToString(", ")}"
        }
        if (missingKeywords.isNotEmpty()) {
            recommendations += "Align layout/component structure: ${missingKeywords.joinToString(", ")}"
        }
        if (totalScore < MEDIUM_SIMILARITY_THRESHOLD) {
            recommendations += "Regenerate and re-run validate_code until similarity is >= $TARGET_SIMILARITY_THRESHOLD."
        }

        val result = buildString {
            appendLine("UI replica validation result")
            appendLine("Similarity: $similarityLabel ($totalScore/100)")
            appendLine("Text recall: ${matchedTexts.size}/${expectedTexts.size}")
            appendLine("Structure recall: ${matchedKeywords.size}/${expectedKeywords.size}")
            if (recommendations.isNotEmpty()) {
                appendLine()
                appendLine("Refinement guidance:")
                recommendations.forEach { appendLine("- $it") }
            }
        }.trim()

        return ToolExecutionResult(output = result, isError = totalScore < MEDIUM_SIMILARITY_THRESHOLD)
    }

    private fun launchPackage(context: Context, packageName: String): String {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return "Failed: no launch intent for $packageName."
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            "Launched $packageName."
        }.getOrElse {
            "Failed to launch $packageName: ${it.message}"
        }
    }

    private suspend fun captureRawDumpViaShizuku(): String? {
        val command = "uiautomator dump /data/local/tmp/omnidev_uidump.xml >/dev/null 2>&1 && cat /data/local/tmp/omnidev_uidump.xml && rm -f /data/local/tmp/omnidev_uidump.xml"
        val result = ShizukuCommandTool.execute(command)
        val output = result.outputOrNull()?.trim().orEmpty()
        if (output.isBlank()) return null
        return output.take(MAX_RAW_DUMP_CHARS)
    }

    private fun extractScreenshotDataUri(output: String): String? {
        val startTag = "[SCREENSHOT_BASE64]"
        val endTag = "[/SCREENSHOT_BASE64]"
        val start = output.indexOf(startTag)
        val end = output.indexOf(endTag)
        if (start == -1 || end == -1 || end <= start) return null
        return output.substring(start + startTag.length, end).trim().ifBlank { null }
    }

    private fun extractExpectedTexts(reference: String): List<String> {
        val semanticQuoted = Regex("\"([^\"]{2,80})\"")
            .findAll(reference)
            .map { it.groupValues[1].trim() }
        val xmlTextAttrs = Regex("""\btext="([^"]{2,80})"""")
            .findAll(reference)
            .map { it.groupValues[1].trim() }
        val xmlDescAttrs = Regex("""\bcontent-desc="([^"]{2,80})"""")
            .findAll(reference)
            .map { it.groupValues[1].trim() }

        return (semanticQuoted + xmlTextAttrs + xmlDescAttrs)
            .map { it.replace("\\s+".toRegex(), " ").trim() }
            .filter { it.isNotBlank() && it != "••••••••" && !it.startsWith("bounds:", ignoreCase = true) }
            .distinct()
            .take(40)
            .toList()
    }

    private fun extractComponentHints(reference: String): List<String> {
        val semanticClasses = Regex("""\[[Nn]\d+\]\s+([A-Za-z0-9_$.]+)""")
            .findAll(reference)
            .map { it.groupValues[1] }
        val xmlClasses = Regex("""\bclass="([^"]+)"""")
            .findAll(reference)
            .map { it.groupValues[1].substringAfterLast('.') }

        return (semanticClasses + xmlClasses)
            .map { normalizeComponentType(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)
            .toList()
    }

    private fun normalizeComponentType(raw: String): String {
        val v = raw.lowercase(Locale.ROOT)
        return when {
            "edittext" in v || "textfield" in v || "input" in v -> "input"
            "button" in v -> "button"
            "image" in v || "icon" in v -> "image"
            "recycler" in v || "list" in v || "lazycolumn" in v -> "list"
            "scroll" in v -> "scroll"
            "textview" in v || "text" in v -> "text"
            "checkbox" in v || "switch" in v || "radio" in v -> "toggle"
            "card" in v -> "card"
            "toolbar" in v || "appbar" in v -> "toolbar"
            else -> ""
        }
    }

    private fun componentKeywordsForLanguage(component: String, targetLanguage: String): List<String> {
        val lang = targetLanguage.lowercase(Locale.ROOT)
        val isCompose = "compose" in lang
        val isReact = "react" in lang || "jsx" in lang || "tsx" in lang
        val isWeb = "html" in lang || "css" in lang || "js" in lang || isReact
        val isXmlAndroid = "xml" in lang && !isWeb

        return when (component) {
            "button" -> when {
                isCompose -> listOf("Button(", "OutlinedButton(", "TextButton(")
                isReact -> listOf("<button", "Button")
                isWeb -> listOf("<button")
                isXmlAndroid -> listOf("<Button", "MaterialButton")
                else -> listOf("button", "Button")
            }
            "input" -> when {
                isCompose -> listOf("TextField(", "OutlinedTextField(")
                isReact -> listOf("<input", "<textarea")
                isWeb -> listOf("<input", "<textarea")
                isXmlAndroid -> listOf("<EditText", "TextInputEditText")
                else -> listOf("input", "TextField", "EditText")
            }
            "image" -> when {
                isCompose -> listOf("Image(", "Icon(")
                isReact -> listOf("<img", "<Image")
                isWeb -> listOf("<img")
                isXmlAndroid -> listOf("<ImageView")
                else -> listOf("image", "ImageView", "img")
            }
            "list" -> when {
                isCompose -> listOf("LazyColumn(", "LazyRow(")
                isReact -> listOf(".map(", "<ul", "<ol")
                isWeb -> listOf("<ul", "<ol")
                isXmlAndroid -> listOf("RecyclerView", "ListView", "NestedScrollView")
                else -> listOf("list", "RecyclerView", "LazyColumn")
            }
            "text" -> when {
                isCompose -> listOf("Text(")
                isReact -> listOf("<span", "<p", "<h1", "<h2", "<h3")
                isWeb -> listOf("<span", "<p", "<h1", "<h2", "<h3")
                isXmlAndroid -> listOf("<TextView")
                else -> listOf("Text(", "TextView", "<p")
            }
            "toggle" -> when {
                isCompose -> listOf("Checkbox(", "Switch(", "RadioButton(")
                isReact || isWeb -> listOf("type=\"checkbox\"", "type=\"radio\"", "switch")
                isXmlAndroid -> listOf("<CheckBox", "<Switch", "<RadioButton")
                else -> listOf("checkbox", "switch", "radio")
            }
            "toolbar" -> when {
                isCompose -> listOf("TopAppBar(", "CenterAlignedTopAppBar(")
                isReact || isWeb -> listOf("<header", "toolbar", "appbar")
                isXmlAndroid -> listOf("Toolbar", "MaterialToolbar", "AppBarLayout")
                else -> listOf("toolbar", "TopAppBar")
            }
            "card" -> when {
                isCompose -> listOf("Card(")
                isReact || isWeb -> listOf("card")
                isXmlAndroid -> listOf("CardView", "MaterialCardView")
                else -> listOf("card", "Card(")
            }
            "scroll" -> when {
                isCompose -> listOf("verticalScroll(", "rememberScrollState(")
                isReact || isWeb -> listOf("overflow", "scroll")
                isXmlAndroid -> listOf("ScrollView", "NestedScrollView")
                else -> listOf("scroll")
            }
            else -> emptyList()
        }
    }
}
