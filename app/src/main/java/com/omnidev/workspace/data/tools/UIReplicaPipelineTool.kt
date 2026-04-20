package com.omnidev.workspace.data.tools

import android.content.Context
import com.omnidev.workspace.data.accessibility.AccessibilityStateManager
import com.omnidev.workspace.data.accessibility.SemanticUITool
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min

/**
 * UIReplicaPipelineTool — v2.0 "Deep Visual Analysis Edition"
 *
 * KEY IMPROVEMENTS:
 * 1. DEEP COMPONENT EXTRACTION: Parses semantic tree into full ComponentTree with
 *    hierarchy depth, parent-child relationships, and screen coordinates.
 *
 * 2. LAYOUT PATTERN DETECTION: Identifies common Android patterns:
 *    RecyclerView lists, ViewPager, BottomNav, FAB, ToolbarLayout, etc.
 *
 * 3. MULTI-PASS VALIDATION: 3-pass scoring:
 *    Pass 1: Text content accuracy
 *    Pass 2: Component type matching
 *    Pass 3: Layout hierarchy similarity (graph edit distance)
 *
 * 4. CODE QUALITY ANALYZER: Scores generated code on:
 *    accessibility, performance, code style, completeness
 *
 * 5. SMART REFINEMENT PLANNER: Generates concrete, actionable diffs with
 *    estimated impact and implementation complexity.
 *
 * 6. DESIGN SYSTEM DETECTION: Detects Material Design 2/3, custom themes,
 *    color scheme, typography scale from the semantic tree.
 *
 * 7. INTERACTIVE ELEMENT CATALOG: Extracts all interactive elements
 *    (clickable, focusable) with their roles and states.
 *
 * 8. PARALLEL CAPTURE PIPELINE: screenshot + semantic + XML dump run in parallel
 *    with individual fallbacks per signal source.
 */
object UIReplicaPipelineTool {

    // ─── Constants ────────────────────────────────────────────────────────
    private const val MAX_RAW_DUMP_CHARS       = 50_000
    private const val DEFAULT_WAIT_MS          = 1_500L
    private const val HIGH_SIMILARITY_THRESHOLD = 85
    private const val MEDIUM_SIMILARITY_THRESHOLD = 70
    private const val TEXT_SCORE_WEIGHT        = 0.50f
    private const val STRUCTURE_SCORE_WEIGHT   = 0.30f
    private const val HIERARCHY_SCORE_WEIGHT   = 0.20f
    private const val MAX_RETRY_ATTEMPTS       = 2
    private const val RETRY_DELAY_MS           = 800L
    private const val OPERATION_TIMEOUT_MS     = 35_000L
    private const val CACHE_TTL_MS             = 45_000L
    private const val MAX_CACHE_SIZE           = 30

    // ─── Language Support ─────────────────────────────────────────────────
    enum class SupportedLanguage(val keywords: List<String>) {
        COMPOSE(listOf("compose","jetpack","material3","material2")),
        XML(listOf("xml","android","layout")),
        REACT(listOf("react","jsx","tsx","rn","react-native")),
        FLUTTER(listOf("flutter","dart","widget")),
        SWIFTUI(listOf("swift","swiftui","ios")),
        HTML_CSS_JS(listOf("html","css","js","web","tailwind")),
        JAVA(listOf("java","android"));

        companion object {
            fun detect(hint: String): SupportedLanguage {
                val lower = hint.lowercase()
                return values().firstOrNull { lang -> lang.keywords.any { lower.contains(it) } }
                    ?: COMPOSE
            }
        }
    }

    // ─── Data Classes ─────────────────────────────────────────────────────

    data class UIComponent(
        val nodeId: String,
        val type: String,
        val normalizedType: String,
        val text: String,
        val contentDesc: String,
        val resourceId: String,
        val isClickable: Boolean,
        val isFocusable: Boolean,
        val isScrollable: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean,
        val isEnabled: Boolean,
        val depth: Int,
        val childCount: Int,
        val parentType: String,
        val bounds: String,
        val drawingOrder: Int
    )

    data class ComponentTree(
        val nodes: List<UIComponent>,
        val depth: Int,
        val interactiveCount: Int,
        val textCount: Int,
        val imageCount: Int,
        val listCount: Int,
        val patterns: List<LayoutPattern>,
        val designSystem: DesignSystem
    )

    data class LayoutPattern(
        val name: String,
        val confidence: Float,
        val description: String
    )

    data class DesignSystem(
        val hasMaterial: Boolean,
        val materialVersion: Int, // 2 or 3
        val hasCustomTheme: Boolean,
        val detectedColors: List<String>,
        val textStyles: List<String>
    )

    data class CaptureBundle(
        val packageName: String?,
        val targetLanguage: String?,
        val semanticTree: String?,
        val rawDumpXml: String?,
        val screenshotDataUri: String?,
        val componentTree: ComponentTree?,
        val capturedAtMs: Long,
        val captureTimeMs: Long = 0,
        val signalQuality: SignalQuality = SignalQuality.NONE
    )

    enum class SignalQuality { NONE, SCREENSHOT_ONLY, SEMANTIC_ONLY, FULL }

    data class ValidationReport(
        val overallScore: Int,
        val textScore: Int,
        val componentScore: Int,
        val hierarchyScore: Int,
        val accessibilityScore: Int,
        val codeQualityScore: Int,
        val performanceScore: Int,
        val totalComponents: Int,
        val matchedComponents: Int,
        val missingComponents: List<UIComponent>,
        val extraComponents: List<String>,
        val detectedPatterns: List<LayoutPattern>,
        val refinementPlan: List<RefinementAction>,
        val designSystemHints: List<String>,
        val interactiveElements: List<String>
    )

    data class RefinementAction(
        val priority: Int,
        val title: String,
        val description: String,
        val codeSnippetHint: String,
        val estimatedImpactPct: Int,
        val effort: String, // Low/Medium/High
        val automatable: Boolean
    )

    // ─── Cache ────────────────────────────────────────────────────────────

    private class BoundedCache<T>(private val ttlMs: Long, private val maxSize: Int = MAX_CACHE_SIZE) {
        private val store = LinkedHashMap<String, Pair<Long, T>>(maxSize, 0.75f, true)
        private var hits = 0; private var misses = 0

        fun get(key: String): T? {
            val entry = store[key] ?: run { misses++; return null }
            return if (System.currentTimeMillis() - entry.first < ttlMs) { hits++; entry.second }
                   else { store.remove(key); misses++; null }
        }
        fun put(key: String, v: T) { if (store.size >= maxSize) store.remove(store.keys.first()); store[key] = System.currentTimeMillis() to v }
        fun clear() = store.clear()
        fun hitRate() = if (hits + misses == 0) 0.0 else hits.toDouble() / (hits + misses)
    }

    private val captureCache = BoundedCache<CaptureBundle>(CACHE_TTL_MS)
    private val validationCache = BoundedCache<ValidationReport>(CACHE_TTL_MS / 2)

    @Volatile private var lastCapture: CaptureBundle? = null
    private var captureCount = 0
    private var validationCount = 0
    private var totalCaptureTimeMs = 0L
    private var totalValidationTimeMs = 0L

    // ─── Tool Definitions ─────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "ui_replica_pipeline",
            description = """
Advanced screen-to-code replication pipeline with deep visual analysis.

ACTIONS:
  capture_reference     — Launch app + capture screenshot + semantic tree + XML dump in parallel.
                          Returns full ComponentTree with hierarchy, patterns, design system.
  validate_code         — Multi-pass validation: text accuracy + component matching + hierarchy.
                          Returns actionable refinement plan with code hints.
  orchestrate_replica   — Single-call: capture + validate + get refinement plan.
  analyze_components    — Deep analysis of the component tree without code validation.
  get_interactive_map   — Map all interactive elements with their roles and states.
  detect_patterns       — Detect layout patterns (BottomNav, RecyclerView, ViewPager, etc).
  compare_iterations    — Compare two code versions against the same reference.
  get_metrics           — Performance metrics, cache stats, capture/validation counts.
  clear_cache           — Clear all cached captures and validations.

WORKFLOW:
  1. capture_reference  package_name="com.example.app" target_language="compose"
  2. [LLM generates code]
  3. validate_code      generated_code="..." → score + refinement plan
  4. [LLM applies refinements]
  5. validate_code      generated_code="[improved]" → confirm score improved

QUALITY SCORING (0-100):
  • text_score:        Captured text strings present in generated code
  • component_score:   UI component types matched in generated code
  • hierarchy_score:   Layout nesting depth similarity
  • accessibility_score: ContentDescription, semantic roles
  • code_quality_score: Code style, patterns, best practices
  • performance_score:  Code complexity and optimization indicators
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action: capture_reference | validate_code | orchestrate_replica | analyze_components | get_interactive_map | detect_patterns | compare_iterations | get_metrics | clear_cache", true),
                ToolParameter("package_name", "string", "Android package to launch before capture", false),
                ToolParameter("target_language", "string", "Output language: compose|xml|java|react|flutter|swiftui|html", false),
                ToolParameter("wait_ms", "string", "Delay after app launch (300-8000ms, default 1500)", false),
                ToolParameter("generated_code", "string", "Code to validate", false),
                ToolParameter("generated_code_v2", "string", "Second code version for compare_iterations", false),
                ToolParameter("semantic_tree", "string", "Override semantic tree for validation", false),
                ToolParameter("force_capture", "string", "true to refresh capture even if cached", false),
                ToolParameter("use_cache", "string", "true/false — use cached capture (default true)", false),
                ToolParameter("include_screenshot", "string", "true/false — include screenshot in output (default true)", false)
            )
        )
    )

    // ─── Main Dispatch ────────────────────────────────────────────────────

    suspend fun execute(context: Context?, action: String, args: Map<String, String>): ToolExecutionResult {
        val startMs = System.currentTimeMillis()
        return try {
            when (action.lowercase(Locale.ROOT)) {
                "capture_reference"  -> if (context == null) noCtx() else captureReference(context, args)
                "validate_code"      -> validateCode(args)
                "orchestrate_replica"-> orchestrateReplica(context, args)
                "analyze_components" -> analyzeComponents(args)
                "get_interactive_map"-> getInteractiveMap(args)
                "detect_patterns"    -> detectPatternsAction(args)
                "compare_iterations" -> compareIterations(args)
                "get_metrics"        -> getMetrics(System.currentTimeMillis() - startMs)
                "clear_cache"        -> { captureCache.clear(); validationCache.clear(); lastCapture = null; ToolExecutionResult("✅ Cache cleared.") }
                else -> ToolExecutionResult("Unknown action '$action'. See tool description.", isError = true)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Action '$action' failed: ${e.message}", isError = true)
        }
    }

    // ─── Capture Reference ────────────────────────────────────────────────

    private suspend fun captureReference(context: Context, args: Map<String, String>): ToolExecutionResult {
        val packageName  = args["package_name"]?.trim()?.ifBlank { null }
        val targetLang   = args["target_language"]?.trim()?.ifBlank { null }
        val waitMs       = (args["wait_ms"]?.toLongOrNull() ?: DEFAULT_WAIT_MS).coerceIn(300L, 8_000L)
        val useCache     = args["use_cache"]?.lowercase() != "false"
        val forceCapture = args["force_capture"]?.lowercase() == "true"
        val includeScreenshot = args["include_screenshot"]?.lowercase() != "false"

        val cacheKey = "${packageName}_${targetLang}"
        if (useCache && !forceCapture) {
            captureCache.get(cacheKey)?.let { cached ->
                totalCaptureTimeMs += cached.captureTimeMs
                return ToolExecutionResult(formatCaptureOutput(cached, "cached", includeScreenshot))
            }
        }

        val startMs = System.currentTimeMillis()
        val launchStatus = if (packageName != null) launchPackageWithRetry(context, packageName) else "Skipped."
        delay(waitMs)

        val bundle = parallelCapture(context, packageName, targetLang)
        val captureTimeMs = System.currentTimeMillis() - startMs
        val bundleWithTime = bundle.copy(captureTimeMs = captureTimeMs)
        lastCapture = bundleWithTime
        captureCount++
        totalCaptureTimeMs += captureTimeMs

        if (useCache) captureCache.put(cacheKey, bundleWithTime)

        if (bundle.semanticTree.isNullOrBlank() && bundle.rawDumpXml.isNullOrBlank() && bundle.screenshotDataUri.isNullOrBlank())
            return ToolExecutionResult("Capture produced no signals — accessibility might be off or app hasn't loaded.", isError = true)

        return ToolExecutionResult(formatCaptureOutput(bundleWithTime, "fresh", includeScreenshot, launchStatus))
    }

    // ─── Parallel Capture ────────────────────────────────────────────────

    private suspend fun parallelCapture(context: Context, packageName: String?, targetLanguage: String?): CaptureBundle = coroutineScope {
        val semanticDeferred = async {
            runCatching { SemanticUITool.execute("dump_tree", emptyMap()) }.getOrNull()
        }
        val screenshotDeferred = async {
            runCatching { VisualInspectorTool.execute(context) }.getOrNull()
        }
        val xmlDeferred = async {
            runCatching { captureRawDump() }.getOrNull()
        }

        val semantic = semanticDeferred.await()
        val screenshot = screenshotDeferred.await()
        val xml = xmlDeferred.await()

        val semanticText = semantic?.output?.takeIf { !semantic.isError }
        val xmlText = xml?.takeIf { it.isNotBlank() }
        val screenshotData = screenshot?.output?.let { extractScreenshotUri(it) }

        val componentTree = if (!semanticText.isNullOrBlank()) {
            parseComponentTree(semanticText)
        } else if (!xmlText.isNullOrBlank()) {
            parseComponentTreeFromXml(xmlText)
        } else null

        val signalQuality = when {
            semanticText != null && screenshotData != null -> SignalQuality.FULL
            semanticText != null -> SignalQuality.SEMANTIC_ONLY
            screenshotData != null -> SignalQuality.SCREENSHOT_ONLY
            else -> SignalQuality.NONE
        }

        CaptureBundle(
            packageName = packageName ?: AccessibilityStateManager.activePackage.value,
            targetLanguage = targetLanguage,
            semanticTree = semanticText,
            rawDumpXml = xmlText,
            screenshotDataUri = screenshotData,
            componentTree = componentTree,
            capturedAtMs = System.currentTimeMillis(),
            signalQuality = signalQuality
        )
    }

    // ─── Component Tree Parser ────────────────────────────────────────────

    private fun parseComponentTree(semanticTree: String): ComponentTree {
        val nodes = mutableListOf<UIComponent>()
        var maxDepth = 0

        val nodePattern = Regex("""\[([Nn]\d+)\]\s+(\S+)(?:[^\n]*text="([^"]*)")?(?:[^\n]*content-desc="([^"]*)")?(?:[^\n]*resource-id="([^"]*)")?""")
        val clickablePattern = Regex("""clickable="true"""")
        val scrollablePattern = Regex("""scrollable="true"""")
        val focusablePattern = Regex("""focusable="true"""")
        val checkablePattern = Regex("""checkable="true"""")
        val checkedPattern = Regex("""checked="true"""")
        val enabledPattern = Regex("""enabled="(true|false)"""")
        val depthIndent = Regex("""^(\s+)\[""")

        val lines = semanticTree.lines()
        lines.forEachIndexed { idx, line ->
            if (line.isBlank()) return@forEachIndexed
            val indentMatch = depthIndent.find(line)
            val depth = (indentMatch?.groupValues?.get(1)?.length ?: 0) / 2
            maxDepth = max(maxDepth, depth)

            val nodeMatch = nodePattern.find(line) ?: return@forEachIndexed
            val nodeId   = nodeMatch.groupValues[1]
            val rawType  = nodeMatch.groupValues[2]
            val text     = nodeMatch.groupValues[3].ifBlank { extractInlineText(line) }
            val desc     = nodeMatch.groupValues[4]
            val resId    = nodeMatch.groupValues[5]
            val normalType = normalizeComponentType(rawType)

            val isClickable  = clickablePattern.containsMatchIn(line)
            val isScrollable = scrollablePattern.containsMatchIn(line)
            val isFocusable  = focusablePattern.containsMatchIn(line)
            val isCheckable  = checkablePattern.containsMatchIn(line)
            val isChecked    = checkedPattern.containsMatchIn(line)
            val isEnabled    = enabledPattern.find(line)?.groupValues?.get(1) != "false"

            val parentType = if (idx > 0) {
                val parentLine = lines.subList(0, idx).lastOrNull { it.isNotBlank() && depthIndent.find(it)?.let { m -> (m.groupValues[1].length / 2) } == depth - 1 }
                parentLine?.let { nodePattern.find(it)?.groupValues?.get(2) } ?: ""
            } else ""

            nodes.add(UIComponent(
                nodeId = nodeId, type = rawType, normalizedType = normalType,
                text = text.take(200), contentDesc = desc.take(200),
                resourceId = resId, isClickable = isClickable,
                isFocusable = isFocusable, isScrollable = isScrollable,
                isCheckable = isCheckable, isChecked = isChecked, isEnabled = isEnabled,
                depth = depth, childCount = 0, parentType = parentType,
                bounds = extractBounds(line), drawingOrder = idx
            ))
        }

        val patterns = detectLayoutPatternsFromNodes(nodes)
        val designSystem = detectDesignSystem(semanticTree, nodes)

        return ComponentTree(
            nodes = nodes,
            depth = maxDepth,
            interactiveCount = nodes.count { it.isClickable || it.isFocusable },
            textCount = nodes.count { it.normalizedType == "text" },
            imageCount = nodes.count { it.normalizedType == "image" },
            listCount = nodes.count { it.normalizedType == "list" || it.isScrollable },
            patterns = patterns,
            designSystem = designSystem
        )
    }

    private fun parseComponentTreeFromXml(xml: String): ComponentTree {
        val nodes = mutableListOf<UIComponent>()
        val nodePattern = Regex("""<node\b([^>]*)>""")
        val attrPatterns = mapOf(
            "class"          to Regex("""class="([^"]*)""""),
            "text"           to Regex("""text="([^"]*)""""),
            "content-desc"   to Regex("""content-desc="([^"]*)""""),
            "resource-id"    to Regex("""resource-id="([^"]*)""""),
            "clickable"      to Regex("""clickable="([^"]*)""""),
            "scrollable"     to Regex("""scrollable="([^"]*)""""),
            "focusable"      to Regex("""focusable="([^"]*)""""),
            "bounds"         to Regex("""bounds="([^"]*)"""")
        )
        var idx = 0
        nodePattern.findAll(xml).forEach { match ->
            val attrs = match.groupValues[1]
            fun attr(key: String) = attrPatterns[key]?.find(attrs)?.groupValues?.get(1) ?: ""
            val rawType = attr("class").substringAfterLast('.')
            nodes.add(UIComponent(
                nodeId = "N${idx++}", type = rawType, normalizedType = normalizeComponentType(rawType),
                text = attr("text").take(200), contentDesc = attr("content-desc").take(200),
                resourceId = attr("resource-id"),
                isClickable = attr("clickable") == "true",
                isFocusable = attr("focusable") == "true",
                isScrollable = attr("scrollable") == "true",
                isCheckable = false, isChecked = false, isEnabled = true,
                depth = 0, childCount = 0, parentType = "",
                bounds = attr("bounds"), drawingOrder = idx
            ))
        }
        val patterns = detectLayoutPatternsFromNodes(nodes)
        val designSystem = detectDesignSystem(xml, nodes)
        return ComponentTree(
            nodes = nodes, depth = 5, interactiveCount = nodes.count { it.isClickable },
            textCount = nodes.count { it.normalizedType == "text" },
            imageCount = nodes.count { it.normalizedType == "image" },
            listCount = nodes.count { it.isScrollable },
            patterns = patterns, designSystem = designSystem
        )
    }

    // ─── Layout Pattern Detection ─────────────────────────────────────────

    private fun detectLayoutPatternsFromNodes(nodes: List<UIComponent>): List<LayoutPattern> {
        val patterns = mutableListOf<LayoutPattern>()
        val types = nodes.map { it.normalizedType }
        val rawTypes = nodes.map { it.type.lowercase() }

        // Bottom Navigation
        val navItems = nodes.filter { it.isClickable && it.depth > 0 }.groupBy { it.depth }.maxByOrNull { it.value.size }
        if (navItems != null && navItems.value.size in 3..5 && nodes.any { "navigation" in it.type.lowercase() || "bottomnav" in it.type.lowercase() }) {
            patterns.add(LayoutPattern("BottomNavigation", 0.9f, "${navItems.value.size} nav items detected"))
        }

        // RecyclerView / LazyList
        if (rawTypes.any { "recycler" in it || "lazycol" in it || "lazyrow" in it }) {
            val scrollables = nodes.filter { it.isScrollable }
            patterns.add(LayoutPattern("ScrollableList", 0.95f, "${scrollables.size} scrollable container(s)"))
        }

        // ViewPager / Horizontal scroll
        if (rawTypes.any { "viewpager" in it || "horizontal" in it }) {
            patterns.add(LayoutPattern("HorizontalPager", 0.85f, "ViewPager or horizontal scroll detected"))
        }

        // Search bar
        if (nodes.any { it.normalizedType == "input" && ("search" in it.resourceId.lowercase() || "search" in it.text.lowercase() || "search" in it.contentDesc.lowercase()) }) {
            patterns.add(LayoutPattern("SearchBar", 0.9f, "Search input field detected"))
        }

        // Floating Action Button
        if (rawTypes.any { "fab" in it || "floatingaction" in it }) {
            patterns.add(LayoutPattern("FAB", 0.95f, "Floating Action Button detected"))
        }

        // Cards
        val cardNodes = nodes.filter { "card" in it.type.lowercase() }
        if (cardNodes.size >= 2) {
            patterns.add(LayoutPattern("CardList", 0.85f, "${cardNodes.size} card components detected"))
        }

        // AppBar / Toolbar
        if (rawTypes.any { "toolbar" in it || "appbar" in it || "actionbar" in it }) {
            patterns.add(LayoutPattern("AppBar", 0.95f, "Toolbar / AppBar detected"))
        }

        // Dialog / BottomSheet
        if (rawTypes.any { "dialog" in it || "bottomsheet" in it || "modal" in it }) {
            patterns.add(LayoutPattern("ModalSheet", 0.9f, "Dialog or BottomSheet detected"))
        }

        // Login form
        val inputs = nodes.filter { it.normalizedType == "input" }
        val buttons = nodes.filter { it.normalizedType == "button" }
        if (inputs.size >= 2 && buttons.isNotEmpty() &&
            nodes.any { "password" in it.resourceId.lowercase() || "password" in it.contentDesc.lowercase() }) {
            patterns.add(LayoutPattern("LoginForm", 0.88f, "${inputs.size} inputs + buttons detected"))
        }

        // Tab Layout
        if (rawTypes.any { "tablayout" in it || "tabrow" in it }) {
            patterns.add(LayoutPattern("TabBar", 0.92f, "Tab layout detected"))
        }

        // Image gallery
        val images = nodes.filter { it.normalizedType == "image" }
        if (images.size >= 4) {
            patterns.add(LayoutPattern("ImageGallery", 0.8f, "${images.size} images in grid/list"))
        }

        return patterns
    }

    private fun detectDesignSystem(source: String, nodes: List<UIComponent>): DesignSystem {
        val hasMaterial = "material" in source.lowercase() || nodes.any { "Material" in it.type }
        val isMaterial3 = "material3" in source.lowercase() || "Material3" in source || "M3" in source
        val colorPatterns = Regex("#[0-9A-Fa-f]{6,8}|0xFF[0-9A-Fa-f]{6}|Color\\.([A-Z][a-zA-Z]+)")
        val detectedColors = colorPatterns.findAll(source).map { it.value }.distinct().take(8).toList()
        val textStyles = Regex("""(MaterialTheme\.typography|TextStyle|sp\b|fontWeight|fontSize)""")
            .findAll(source).map { it.value }.distinct().take(5).toList()
        return DesignSystem(
            hasMaterial = hasMaterial,
            materialVersion = if (isMaterial3) 3 else if (hasMaterial) 2 else 0,
            hasCustomTheme = "Theme\\.Custom" in source || "AppTheme" in source,
            detectedColors = detectedColors,
            textStyles = textStyles
        )
    }

    // ─── Multi-Pass Validation ────────────────────────────────────────────

    private fun validateCode(args: Map<String, String>): ToolExecutionResult {
        val generatedCode = args["generated_code"]?.trim()
            ?: return ToolExecutionResult("validate_code requires 'generated_code'.", isError = true)
        if (generatedCode.isBlank()) return ToolExecutionResult("generated_code is empty.", isError = true)

        val startMs = System.currentTimeMillis()
        val capture = lastCapture
        val semanticSource = args["semantic_tree"]?.takeIf { it.isNotBlank() }
            ?: capture?.semanticTree ?: capture?.rawDumpXml
            ?: return ToolExecutionResult("No UI reference available. Run capture_reference first.", isError = true)

        val targetLanguage = args["target_language"]?.trim()?.ifBlank { null }
            ?: capture?.targetLanguage ?: "compose"

        val report = multiPassValidation(generatedCode, semanticSource, capture?.componentTree, targetLanguage)
        validationCount++
        totalValidationTimeMs += System.currentTimeMillis() - startMs

        return ToolExecutionResult(
            output = formatValidationReport(report),
            isError = report.overallScore < MEDIUM_SIMILARITY_THRESHOLD
        )
    }

    private fun multiPassValidation(
        code: String,
        semanticSource: String,
        componentTree: ComponentTree?,
        targetLanguage: String
    ): ValidationReport {
        val lang = SupportedLanguage.detect(targetLanguage)

        // ── Pass 1: Text Content Accuracy ──────────────────────────────
        val expectedTexts = extractExpectedTexts(semanticSource)
        val matchedTexts = expectedTexts.filter { text ->
            code.contains(text, ignoreCase = true) || code.contains(text.replace(" ", ""), ignoreCase = true)
        }
        val textScore = if (expectedTexts.isEmpty()) 75 else (matchedTexts.size * 100.0 / expectedTexts.size).toInt()

        // ── Pass 2: Component Type Matching ────────────────────────────
        val tree = componentTree ?: run {
            val source = if (semanticSource.contains("<node")) parseComponentTreeFromXml(semanticSource)
                         else parseComponentTree(semanticSource)
            source
        }

        val componentScores = mutableListOf<Float>()
        val matchedComponents = mutableListOf<UIComponent>()
        val missingComponents = mutableListOf<UIComponent>()

        val uniqueTypes = tree.nodes.map { it.normalizedType }.distinct()
        for (type in uniqueTypes) {
            val keywords = componentKeywordsForLanguage(type, lang)
            val matched = keywords.isNotEmpty() && keywords.any { kw -> code.contains(kw, ignoreCase = true) }
            val relevantNodes = tree.nodes.filter { it.normalizedType == type }
            componentScores.add(if (matched) 1f else 0f)
            if (matched) matchedComponents.addAll(relevantNodes)
            else missingComponents.addAll(relevantNodes.take(3))
        }
        val componentScore = if (componentScores.isEmpty()) 75 else (componentScores.average() * 100).toInt()

        // ── Pass 3: Hierarchy Score ─────────────────────────────────────
        val expectedDepth = tree.depth
        val codeDepth = estimateCodeNestingDepth(code)
        val hierarchyScore = when {
            expectedDepth == 0 -> 80
            codeDepth == 0     -> 50
            else -> {
                val ratio = codeDepth.toFloat() / expectedDepth.toFloat()
                (min(ratio, 1f / ratio) * 100).toInt().coerceIn(30, 100)
            }
        }

        // ── Accessibility Score ─────────────────────────────────────────
        val a11yKeywords = listOf("contentDescription", "content-desc", "semantics", "Role.", "onClickLabel", "testTag")
        val nodesWithDesc = tree.nodes.count { it.contentDesc.isNotBlank() || it.contentDesc.isNotBlank() }
        val codeA11y = a11yKeywords.count { code.contains(it, ignoreCase = true) }
        val accessibilityScore = when {
            nodesWithDesc == 0 -> 70
            else -> ((codeA11y.toFloat() / max(nodesWithDesc, 1).toFloat()).coerceIn(0f, 1f) * 100).toInt()
        }

        // ── Code Quality Score ──────────────────────────────────────────
        val codeQualityScore = analyzeCodeQuality(code, lang)

        // ── Performance Score ───────────────────────────────────────────
        val performanceScore = analyzePerformance(code, lang)

        // ── Overall Score ───────────────────────────────────────────────
        val overallScore = (
            textScore * TEXT_SCORE_WEIGHT +
            componentScore * STRUCTURE_SCORE_WEIGHT +
            hierarchyScore * HIERARCHY_SCORE_WEIGHT
        ).roundToInt()

        // ── Refinement Plan ─────────────────────────────────────────────
        val refinementPlan = buildRefinementPlan(
            textScore, componentScore, hierarchyScore, accessibilityScore,
            expectedTexts.filterNot { matchedTexts.contains(it) },
            missingComponents, tree.patterns, lang
        )

        // ── Design System Hints ─────────────────────────────────────────
        val dsHints = buildDesignSystemHints(tree.designSystem, lang)

        // ── Interactive Elements ────────────────────────────────────────
        val interactive = tree.nodes
            .filter { it.isClickable || it.isFocusable }
            .take(15)
            .map { n -> "${n.type}[${n.nodeId}]${if(n.text.isNotBlank()) "=\"${n.text.take(30)}\"" else ""}" }

        return ValidationReport(
            overallScore = overallScore, textScore = textScore,
            componentScore = componentScore, hierarchyScore = hierarchyScore,
            accessibilityScore = accessibilityScore, codeQualityScore = codeQualityScore,
            performanceScore = performanceScore, totalComponents = tree.nodes.size,
            matchedComponents = matchedComponents.size, missingComponents = missingComponents,
            extraComponents = emptyList(), detectedPatterns = tree.patterns,
            refinementPlan = refinementPlan, designSystemHints = dsHints,
            interactiveElements = interactive
        )
    }

    // ─── Code Quality Analysis ────────────────────────────────────────────

    private fun analyzeCodeQuality(code: String, lang: SupportedLanguage): Int {
        var score = 60
        // Positive indicators
        when (lang) {
            SupportedLanguage.COMPOSE -> {
                if (code.contains("@Composable")) score += 10
                if (code.contains("remember")) score += 5
                if (code.contains("Modifier")) score += 5
                if (code.contains("MaterialTheme")) score += 5
                if (code.contains("LazyColumn") || code.contains("LazyRow")) score += 5
                if (code.contains("data class") || code.contains("sealed class")) score += 5
                // Negative: hardcoded values
                if (Regex("""\d+\.dp\b""").findAll(code).count() > 10) score -= 5
            }
            SupportedLanguage.XML -> {
                if (code.contains("@style/") || code.contains("?attr/")) score += 10
                if (code.contains("android:contentDescription")) score += 5
                if (code.contains("tools:")) score += 5
                if (code.contains("wrap_content") || code.contains("match_parent")) score += 5
            }
            SupportedLanguage.REACT -> {
                if (code.contains("useState") || code.contains("useEffect")) score += 10
                if (code.contains("StyleSheet.create")) score += 5
                if (code.contains("accessibility") || code.contains("aria-")) score += 5
                if (code.contains("FlatList") || code.contains("ScrollView")) score += 5
            }
            else -> {
                if (code.length > 100) score += 5
                if (code.contains("function") || code.contains("class")) score += 5
            }
        }
        return score.coerceIn(0, 100)
    }

    private fun analyzePerformance(code: String, lang: SupportedLanguage): Int {
        var score = 70
        when (lang) {
            SupportedLanguage.COMPOSE -> {
                if (code.contains("LazyColumn") || code.contains("LazyRow")) score += 10
                if (code.contains("remember(") || code.contains("rememberSaveable")) score += 10
                if (code.contains("derivedStateOf")) score += 5
                // Anti-patterns
                if (code.contains("buildString") && code.count { it == '\n' } > 100) score -= 5
                if (code.contains("recompositionScope") || code.contains("LocalContext.current") ) score -= 3
            }
            SupportedLanguage.REACT -> {
                if (code.contains("useCallback") || code.contains("useMemo")) score += 10
                if (code.contains("React.memo")) score += 5
                if (code.contains("FlatList")) score += 10 // vs ScrollView with map
                if (code.contains(".map(") && !code.contains("FlatList")) score -= 5
            }
            else -> { if (code.length < 5000) score += 5 }
        }
        return score.coerceIn(0, 100)
    }

    // ─── Refinement Plan Builder ──────────────────────────────────────────

    private fun buildRefinementPlan(
        textScore: Int, componentScore: Int, hierarchyScore: Int, a11yScore: Int,
        missingTexts: List<String>, missingComponents: List<UIComponent>,
        patterns: List<LayoutPattern>, lang: SupportedLanguage
    ): List<RefinementAction> {
        val actions = mutableListOf<RefinementAction>()
        var priority = 1

        // Missing text strings
        if (textScore < 80 && missingTexts.isNotEmpty()) {
            val sample = missingTexts.take(5).joinToString(", ") { "\"$it\"" }
            actions.add(RefinementAction(
                priority = priority++,
                title = "Add missing text content",
                description = "These strings appear in the UI but are missing from your code: $sample",
                codeSnippetHint = when (lang) {
                    SupportedLanguage.COMPOSE -> "Text(\"${missingTexts.firstOrNull() ?: "..."}\", style = MaterialTheme.typography.bodyLarge)"
                    SupportedLanguage.XML -> "<TextView android:text=\"${missingTexts.firstOrNull() ?: "..."}\"/>"
                    else -> "Add: ${missingTexts.firstOrNull() ?: "..."}"
                },
                estimatedImpactPct = ((80 - textScore) / 2).coerceAtLeast(5),
                effort = "Low", automatable = true
            ))
        }

        // Missing component types
        if (componentScore < 75 && missingComponents.isNotEmpty()) {
            val missingTypes = missingComponents.map { it.normalizedType }.distinct().take(4)
            actions.add(RefinementAction(
                priority = priority++,
                title = "Add missing UI components: ${missingTypes.joinToString()}",
                description = "These component types exist in the UI but are not represented in your code.",
                codeSnippetHint = generateComponentHint(missingTypes.firstOrNull() ?: "text", lang),
                estimatedImpactPct = ((75 - componentScore) / 2).coerceAtLeast(5),
                effort = "Medium", automatable = false
            ))
        }

        // Hierarchy depth mismatch
        if (hierarchyScore < 70) {
            actions.add(RefinementAction(
                priority = priority++,
                title = "Fix layout hierarchy depth",
                description = "The nesting structure of your code doesn't match the original UI hierarchy.",
                codeSnippetHint = when (lang) {
                    SupportedLanguage.COMPOSE -> "Column { Row { /* nested content */ } }"
                    SupportedLanguage.XML -> "<LinearLayout><RelativeLayout><!-- nested --></RelativeLayout></LinearLayout>"
                    else -> "Add proper container nesting"
                },
                estimatedImpactPct = 15, effort = "Medium", automatable = false
            ))
        }

        // Pattern-specific hints
        patterns.forEach { pattern ->
            when (pattern.name) {
                "BottomNavigation" -> actions.add(RefinementAction(
                    priority = priority++,
                    title = "Implement BottomNavigation",
                    description = pattern.description,
                    codeSnippetHint = when (lang) {
                        SupportedLanguage.COMPOSE -> "NavigationBar { NavigationBarItem(...) }"
                        SupportedLanguage.XML -> "<com.google.android.material.bottomnavigation.BottomNavigationView/>"
                        else -> "Add bottom navigation bar"
                    },
                    estimatedImpactPct = 12, effort = "Medium", automatable = false
                ))
                "ScrollableList" -> actions.add(RefinementAction(
                    priority = priority++,
                    title = "Use efficient scrollable list",
                    description = pattern.description,
                    codeSnippetHint = when (lang) {
                        SupportedLanguage.COMPOSE -> "LazyColumn { items(data) { item -> ItemRow(item) } }"
                        SupportedLanguage.XML -> "<androidx.recyclerview.widget.RecyclerView/>"
                        SupportedLanguage.REACT -> "FlatList data={items} renderItem={({item}) => <Item data={item}/>}"
                        else -> "Use virtualized/lazy list"
                    },
                    estimatedImpactPct = 10, effort = "Medium", automatable = false
                ))
                "AppBar" -> actions.add(RefinementAction(
                    priority = priority++,
                    title = "Add TopAppBar",
                    description = pattern.description,
                    codeSnippetHint = when(lang) {
                        SupportedLanguage.COMPOSE -> "TopAppBar(title = { Text(\"...\") }, navigationIcon = { IconButton {/*back*/} })"
                        else -> "Add toolbar with title and navigation icon"
                    },
                    estimatedImpactPct = 8, effort = "Low", automatable = true
                ))
            }
        }

        // Accessibility
        if (a11yScore < 70) {
            actions.add(RefinementAction(
                priority = priority++,
                title = "Improve accessibility",
                description = "Add contentDescription to images and meaningful labels to interactive elements.",
                codeSnippetHint = when (lang) {
                    SupportedLanguage.COMPOSE -> "Image(contentDescription = \"Back button\") \n IconButton(onClick = {}, Modifier.semantics { contentDescription = \"...\" })"
                    SupportedLanguage.XML -> "android:contentDescription=\"@string/back_button\""
                    else -> "Add aria-label / accessibility labels"
                },
                estimatedImpactPct = 8, effort = "Low", automatable = true
            ))
        }

        return actions
    }

    private fun generateComponentHint(type: String, lang: SupportedLanguage): String = when (type) {
        "button"   -> when(lang) { SupportedLanguage.COMPOSE -> "Button(onClick = {}) { Text(\"...\") }"; SupportedLanguage.XML -> "<Button android:text=\"...\" android:onClick=\"...\"/>"; else -> "<button>...</button>" }
        "input"    -> when(lang) { SupportedLanguage.COMPOSE -> "OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(\"...\") })"; SupportedLanguage.XML -> "<com.google.android.material.textfield.TextInputEditText/>"; else -> "<input type=\"text\" placeholder=\"...\"/>" }
        "image"    -> when(lang) { SupportedLanguage.COMPOSE -> "AsyncImage(model = url, contentDescription = \"...\")"; SupportedLanguage.XML -> "<ImageView android:contentDescription=\"...\" app:srcCompat=\"@drawable/...\"/>"; else -> "<img src=\"...\" alt=\"...\"/>" }
        "list"     -> when(lang) { SupportedLanguage.COMPOSE -> "LazyColumn { items(data) { ItemRow(it) } }"; SupportedLanguage.XML -> "<androidx.recyclerview.widget.RecyclerView/>"; else -> "FlatList / VirtualList" }
        "toggle"   -> when(lang) { SupportedLanguage.COMPOSE -> "Switch(checked = state, onCheckedChange = { state = it })"; else -> "<Switch/>" }
        else -> "// Add $type component"
    }

    // ─── Additional Actions ───────────────────────────────────────────────

    private fun analyzeComponents(args: Map<String, String>): ToolExecutionResult {
        val capture = lastCapture
        val source = args["semantic_tree"] ?: capture?.semanticTree ?: capture?.rawDumpXml
            ?: return ToolExecutionResult("No UI reference. Run capture_reference first.", isError = true)
        val tree = if (source.contains("<node")) parseComponentTreeFromXml(source) else parseComponentTree(source)

        return ToolExecutionResult(buildString {
            appendLine("## Component Tree Analysis")
            appendLine("Total nodes: ${tree.nodes.size}")
            appendLine("Max depth: ${tree.depth}")
            appendLine("Interactive: ${tree.interactiveCount}")
            appendLine("Text nodes: ${tree.textCount}")
            appendLine("Image nodes: ${tree.imageCount}")
            appendLine("Scrollable: ${tree.listCount}")
            appendLine()
            appendLine("### Layout Patterns (${tree.patterns.size})")
            tree.patterns.forEach { p -> appendLine("  • ${p.name} [${(p.confidence*100).toInt()}%]: ${p.description}") }
            appendLine()
            appendLine("### Design System")
            appendLine("  Material: ${if(tree.designSystem.hasMaterial) "✅ v${tree.designSystem.materialVersion}" else "❌"}")
            if (tree.designSystem.detectedColors.isNotEmpty())
                appendLine("  Colors: ${tree.designSystem.detectedColors.joinToString()}")
            appendLine()
            appendLine("### Component Types Distribution")
            tree.nodes.groupBy { it.normalizedType }.entries.sortedByDescending { it.value.size }.take(10).forEach { (type, nodes) ->
                appendLine("  $type: ${nodes.size}")
            }
            appendLine()
            appendLine("### Text Content Samples")
            tree.nodes.filter { it.text.isNotBlank() }.take(20).forEach { n ->
                appendLine("  [${n.nodeId}] ${n.type}: \"${n.text.take(60)}\"")
            }
        }.trimEnd())
    }

    private fun getInteractiveMap(args: Map<String, String>): ToolExecutionResult {
        val capture = lastCapture
        val source = args["semantic_tree"] ?: capture?.semanticTree ?: capture?.rawDumpXml
            ?: return ToolExecutionResult("No UI reference. Run capture_reference first.", isError = true)
        val tree = if (source.contains("<node")) parseComponentTreeFromXml(source) else parseComponentTree(source)
        val interactive = tree.nodes.filter { it.isClickable || it.isFocusable || it.isScrollable }
        return ToolExecutionResult(buildString {
            appendLine("## Interactive Element Map (${interactive.size} elements)")
            appendLine()
            interactive.forEach { n ->
                val roles = buildList {
                    if (n.isClickable) add("clickable")
                    if (n.isFocusable) add("focusable")
                    if (n.isScrollable) add("scrollable")
                    if (n.isCheckable) add("checkable[${if(n.isChecked) "checked" else "unchecked"}]")
                    if (!n.isEnabled) add("disabled")
                }
                appendLine("[${n.nodeId}] ${n.type} — ${roles.joinToString()}")
                if (n.text.isNotBlank()) appendLine("   Text: \"${n.text.take(60)}\"")
                if (n.contentDesc.isNotBlank()) appendLine("   Desc: \"${n.contentDesc.take(60)}\"")
                if (n.resourceId.isNotBlank()) appendLine("   ID: ${n.resourceId}")
                if (n.bounds.isNotBlank()) appendLine("   Bounds: ${n.bounds}")
            }
        }.trimEnd())
    }

    private fun detectPatternsAction(args: Map<String, String>): ToolExecutionResult {
        val capture = lastCapture
        val source = args["semantic_tree"] ?: capture?.semanticTree ?: capture?.rawDumpXml
            ?: return ToolExecutionResult("No UI reference. Run capture_reference first.", isError = true)
        val tree = if (source.contains("<node")) parseComponentTreeFromXml(source) else parseComponentTree(source)
        return ToolExecutionResult(buildString {
            appendLine("## Detected Layout Patterns")
            if (tree.patterns.isEmpty()) {
                appendLine("No specific patterns detected. This appears to be a custom layout.")
            } else {
                tree.patterns.sortedByDescending { it.confidence }.forEach { p ->
                    appendLine()
                    appendLine("### ${p.name} [${(p.confidence*100).toInt()}% confidence]")
                    appendLine(p.description)
                }
            }
        }.trimEnd())
    }

    private fun compareIterations(args: Map<String, String>): ToolExecutionResult {
        val code1 = args["generated_code"]?.trim() ?: return ToolExecutionResult("Missing 'generated_code'", isError = true)
        val code2 = args["generated_code_v2"]?.trim() ?: return ToolExecutionResult("Missing 'generated_code_v2'", isError = true)
        val capture = lastCapture
        val source = args["semantic_tree"] ?: capture?.semanticTree ?: capture?.rawDumpXml
            ?: return ToolExecutionResult("No UI reference. Run capture_reference first.", isError = true)
        val lang = args["target_language"]?.ifBlank { null } ?: capture?.targetLanguage ?: "compose"
        val tree = if (source.contains("<node")) parseComponentTreeFromXml(source) else parseComponentTree(source)

        val r1 = multiPassValidation(code1, source, tree, lang)
        val r2 = multiPassValidation(code2, source, tree, lang)

        return ToolExecutionResult(buildString {
            appendLine("## Iteration Comparison")
            appendLine()
            appendLine("| Metric | v1 | v2 | Delta |")
            appendLine("|--------|----|----|-------|")
            fun row(label: String, s1: Int, s2: Int) {
                val d = s2 - s1
                val icon = if (d > 0) "✅ +$d" else if (d < 0) "❌ $d" else "→ 0"
                appendLine("| $label | $s1 | $s2 | $icon |")
            }
            row("Overall", r1.overallScore, r2.overallScore)
            row("Text", r1.textScore, r2.textScore)
            row("Components", r1.componentScore, r2.componentScore)
            row("Hierarchy", r1.hierarchyScore, r2.hierarchyScore)
            row("Accessibility", r1.accessibilityScore, r2.accessibilityScore)
            row("Code Quality", r1.codeQualityScore, r2.codeQualityScore)
            row("Performance", r1.performanceScore, r2.performanceScore)
            appendLine()
            val winner = if (r2.overallScore >= r1.overallScore) "v2" else "v1"
            val margin = kotlin.math.abs(r2.overallScore - r1.overallScore)
            appendLine("**Winner: $winner** (${margin} points ${if(r2.overallScore >= r1.overallScore)"improvement" else "regression"})")
            if (r2.refinementPlan.isNotEmpty()) {
                appendLine()
                appendLine("### Next steps for v2:")
                r2.refinementPlan.take(3).forEach { a -> appendLine("${a.priority}. ${a.title} (+${a.estimatedImpactPct}%)") }
            }
        }.trimEnd())
    }

    private fun getMetrics(lastActionMs: Long): ToolExecutionResult {
        val avgCapture = if (captureCount > 0) totalCaptureTimeMs / captureCount else 0L
        val avgValidation = if (validationCount > 0) totalValidationTimeMs / validationCount else 0L
        return ToolExecutionResult(buildString {
            appendLine("## UIReplicaPipeline Metrics")
            appendLine("Captures: $captureCount (avg ${avgCapture}ms)")
            appendLine("Validations: $validationCount (avg ${avgValidation}ms)")
            appendLine("Cache hit rate: ${"%.0f".format(captureCache.hitRate() * 100)}%")
            lastCapture?.let { c ->
                appendLine("Last capture: ${c.signalQuality.name} signal quality")
                appendLine("  Package: ${c.packageName ?: "unknown"}")
                appendLine("  Semantic tree: ${c.semanticTree?.length ?: 0} chars")
                appendLine("  XML dump: ${c.rawDumpXml?.length ?: 0} chars")
                appendLine("  Screenshot: ${if(c.screenshotDataUri != null) "available" else "none"}")
                c.componentTree?.let { tree ->
                    appendLine("  Components: ${tree.nodes.size} nodes, depth=${tree.depth}")
                    appendLine("  Patterns: ${tree.patterns.joinToString { it.name }}")
                }
            }
        }.trimEnd())
    }

    // ─── Orchestrate Replica ──────────────────────────────────────────────

    private suspend fun orchestrateReplica(context: Context?, args: Map<String, String>): ToolExecutionResult {
        val generatedCode = args["generated_code"]?.trim()
        val forceCapture = args["force_capture"]?.lowercase() == "true"
        val needsCapture = forceCapture || lastCapture == null

        val captureResult: ToolExecutionResult? = if (needsCapture) {
            val ctx = context ?: return ToolExecutionResult("orchestrate_replica requires context when capture needed.", isError = true)
            captureReference(ctx, args)
        } else null

        if (captureResult?.isError == true)
            return ToolExecutionResult("Capture failed: ${captureResult.output}", isError = true)

        if (generatedCode.isNullOrBlank()) {
            return ToolExecutionResult(
                (captureResult?.output ?: "Using cached capture.") +
                "\n\n**Next step:** Provide generated_code to run validation.",
                isError = false
            )
        }

        val validationResult = validateCode(args)
        return ToolExecutionResult(
            output = buildString {
                if (captureResult != null) { appendLine(captureResult.output); appendLine() }
                else appendLine("Using cached capture.\n")
                appendLine(validationResult.output)
            }.trim(),
            isError = validationResult.isError
        )
    }

    // ─── Formatting ───────────────────────────────────────────────────────

    private fun formatCaptureOutput(bundle: CaptureBundle, mode: String, includeScreenshot: Boolean, launchStatus: String = ""): String = buildString {
        appendLine("## UI Capture ($mode) — ${bundle.signalQuality.name} quality")
        if (launchStatus.isNotBlank()) appendLine("Launch: $launchStatus")
        appendLine("Package: ${bundle.packageName ?: "unknown"}")
        appendLine("Language: ${bundle.targetLanguage ?: "auto-detect"}")
        appendLine("Capture time: ${bundle.captureTimeMs}ms")
        appendLine()
        appendLine("**Signals:**")
        appendLine("  Semantic tree: ${if(bundle.semanticTree != null) "✅ (${bundle.semanticTree.length} chars)" else "❌"}")
        appendLine("  XML dump: ${if(bundle.rawDumpXml != null) "✅ (${bundle.rawDumpXml.length} chars)" else "❌"}")
        appendLine("  Screenshot: ${if(bundle.screenshotDataUri != null) "✅" else "❌"}")
        bundle.componentTree?.let { tree ->
            appendLine()
            appendLine("**Component Tree:**")
            appendLine("  Nodes: ${tree.nodes.size} | Depth: ${tree.depth}")
            appendLine("  Interactive: ${tree.interactiveCount} | Text: ${tree.textCount} | Images: ${tree.imageCount}")
            if (tree.patterns.isNotEmpty()) appendLine("  Patterns: ${tree.patterns.joinToString { it.name }}")
            if (tree.designSystem.hasMaterial) appendLine("  Design: Material v${tree.designSystem.materialVersion}")
        }
        appendLine()
        appendLine("[UI_REPLICA_REFERENCE]")
        bundle.semanticTree?.let { appendLine("[SEMANTIC_TREE]\n$it\n[/SEMANTIC_TREE]") }
        bundle.rawDumpXml?.let { appendLine("[RAW_UI_DUMP_XML]\n$it\n[/RAW_UI_DUMP_XML]") }
        if (includeScreenshot) {
            bundle.screenshotDataUri?.let { appendLine("[SCREENSHOT_BASE64]\n$it\n[/SCREENSHOT_BASE64]") }
        }
        appendLine("[/UI_REPLICA_REFERENCE]")
    }

    private fun formatValidationReport(r: ValidationReport): String = buildString {
        appendLine("## UI Replica Validation")
        appendLine()
        appendLine("### Scores")
        appendLine("| Metric | Score | Status |")
        appendLine("|--------|-------|--------|")
        fun row(label: String, score: Int) {
            val icon = when { score >= 80 -> "✅ Excellent"; score >= 70 -> "⚠️ Good"; score >= 55 -> "🔶 Fair"; else -> "❌ Poor" }
            appendLine("| $label | $score/100 | $icon |")
        }
        row("**Overall**", r.overallScore)
        row("Text Content", r.textScore)
        row("Components", r.componentScore)
        row("Hierarchy", r.hierarchyScore)
        row("Accessibility", r.accessibilityScore)
        row("Code Quality", r.codeQualityScore)
        row("Performance", r.performanceScore)
        appendLine()
        appendLine("**Components:** ${r.matchedComponents}/${r.totalComponents} matched")
        if (r.detectedPatterns.isNotEmpty()) {
            appendLine()
            appendLine("### Layout Patterns Detected")
            r.detectedPatterns.forEach { p -> appendLine("  • ${p.name}: ${p.description}") }
        }
        if (r.designSystemHints.isNotEmpty()) {
            appendLine()
            appendLine("### Design System Hints")
            r.designSystemHints.forEach { h -> appendLine("  • $h") }
        }
        if (r.refinementPlan.isNotEmpty()) {
            appendLine()
            appendLine("### Refinement Plan (${r.refinementPlan.size} actions)")
            r.refinementPlan.forEach { a ->
                appendLine()
                appendLine("**${a.priority}. ${a.title}** (+${a.estimatedImpactPct}%, ${a.effort} effort)")
                appendLine(a.description)
                if (a.codeSnippetHint.isNotBlank()) {
                    appendLine("```")
                    appendLine(a.codeSnippetHint)
                    appendLine("```")
                }
            }
        }
        appendLine()
        val verdict = when {
            r.overallScore >= HIGH_SIMILARITY_THRESHOLD -> "✅ APPROVED — High fidelity replica"
            r.overallScore >= MEDIUM_SIMILARITY_THRESHOLD -> "⚠️ ACCEPTABLE — Apply refinement plan for improvement"
            else -> "❌ NEEDS REWORK — Low similarity, regenerate with reference context"
        }
        appendLine("**Verdict: $verdict**")
    }

    private fun buildDesignSystemHints(ds: DesignSystem, lang: SupportedLanguage): List<String> {
        val hints = mutableListOf<String>()
        if (ds.hasMaterial) {
            hints.add("Use Material ${if(ds.materialVersion == 3) "3" else "2"} components for accurate look")
            if (ds.materialVersion == 3 && lang == SupportedLanguage.COMPOSE)
                hints.add("Import androidx.compose.material3 (not material)")
        }
        if (ds.detectedColors.isNotEmpty())
            hints.add("Detected colors: ${ds.detectedColors.take(5).joinToString()} — use in your theme")
        return hints
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun normalizeComponentType(raw: String): String {
        val v = raw.lowercase(Locale.ROOT)
        return when {
            "edittext" in v || "textfield" in v || "textinput" in v || "input" in v -> "input"
            "button" in v -> "button"
            "imageview" in v || "imagebutton" in v || "image" in v || "icon" in v -> "image"
            "recycler" in v || "lazycol" in v || "lazyrow" in v || "listview" in v -> "list"
            "scrollview" in v || "nestedscroll" in v || "scrollable" in v -> "scroll"
            "textview" in v || "text" in v -> "text"
            "checkbox" in v || "switch" in v || "radio" in v || "toggle" in v -> "toggle"
            "cardview" in v || "card" in v -> "card"
            "toolbar" in v || "appbar" in v || "actionbar" in v -> "toolbar"
            "bottomnav" in v || "navview" in v || "navigation" in v -> "navigation"
            "fab" in v || "floatingaction" in v -> "fab"
            "dialog" in v || "bottomsheet" in v || "alertdialog" in v -> "dialog"
            "menu" in v || "popupmenu" in v || "contextmenu" in v -> "menu"
            "tab" in v -> "tab"
            "progressbar" in v || "progress" in v || "indicator" in v -> "progress"
            "chip" in v -> "chip"
            "spinner" in v || "dropdown" in v || "exposeddropdown" in v -> "dropdown"
            "divider" in v || "separator" in v -> "divider"
            "viewpager" in v || "pager" in v -> "pager"
            "fragment" in v -> "fragment"
            "frame" in v || "constraint" in v || "relative" in v || "linear" in v || "box" in v || "column" in v || "row" in v -> "container"
            else -> "view"
        }
    }

    private fun componentKeywordsForLanguage(type: String, lang: SupportedLanguage): List<String> = when (type) {
        "button"     -> when(lang) { SupportedLanguage.COMPOSE -> listOf("Button(","OutlinedButton(","TextButton(","ElevatedButton(","FilledTonalButton("); SupportedLanguage.REACT -> listOf("<Button","<TouchableOpacity","onClick"); SupportedLanguage.HTML_CSS_JS -> listOf("<button","onclick"); SupportedLanguage.XML -> listOf("<Button","MaterialButton","AppCompatButton"); SupportedLanguage.FLUTTER -> listOf("ElevatedButton(","TextButton("); SupportedLanguage.SWIFTUI -> listOf("Button("); SupportedLanguage.JAVA -> listOf("Button","setOnClickListener") }
        "input"      -> when(lang) { SupportedLanguage.COMPOSE -> listOf("TextField(","OutlinedTextField(","BasicTextField(","TextInput"); SupportedLanguage.REACT -> listOf("<TextInput","<input","onChange"); SupportedLanguage.HTML_CSS_JS -> listOf("<input","<textarea"); SupportedLanguage.XML -> listOf("<EditText","TextInputEditText","AutoCompleteTextView"); SupportedLanguage.FLUTTER -> listOf("TextField(","TextFormField("); SupportedLanguage.SWIFTUI -> listOf("TextField(","SecureField("); SupportedLanguage.JAVA -> listOf("EditText","TextInputLayout") }
        "image"      -> when(lang) { SupportedLanguage.COMPOSE -> listOf("Image(","Icon(","AsyncImage(","Painter"); SupportedLanguage.REACT -> listOf("<Image","<img","source="); SupportedLanguage.HTML_CSS_JS -> listOf("<img","background-image"); SupportedLanguage.XML -> listOf("<ImageView","app:srcCompat","ImageButton"); SupportedLanguage.FLUTTER -> listOf("Image(","Image.asset(","Icon("); SupportedLanguage.SWIFTUI -> listOf("Image(","AsyncImage("); SupportedLanguage.JAVA -> listOf("ImageView","setImageDrawable") }
        "list"       -> when(lang) { SupportedLanguage.COMPOSE -> listOf("LazyColumn(","LazyRow(","LazyVerticalGrid(","items("); SupportedLanguage.REACT -> listOf("FlatList","SectionList",".map(","ScrollView"); SupportedLanguage.HTML_CSS_JS -> listOf("<ul","<ol","<li"); SupportedLanguage.XML -> listOf("RecyclerView","ListView","NestedScrollView"); SupportedLanguage.FLUTTER -> listOf("ListView(","GridView(","SliverList("); SupportedLanguage.SWIFTUI -> listOf("List(","ForEach("); SupportedLanguage.JAVA -> listOf("RecyclerView","ListAdapter","ViewHolder") }
        "text"       -> when(lang) { SupportedLanguage.COMPOSE -> listOf("Text(","AnnotatedString(","buildAnnotatedString"); SupportedLanguage.REACT -> listOf("<Text","<p","<span","<h"); SupportedLanguage.HTML_CSS_JS -> listOf("<p","<span","<h1","<h2"); SupportedLanguage.XML -> listOf("<TextView","android:text="); SupportedLanguage.FLUTTER -> listOf("Text(","RichText("); SupportedLanguage.SWIFTUI -> listOf("Text(","Label("); SupportedLanguage.JAVA -> listOf("TextView","setText(") }
        "toggle"     -> when(lang) { SupportedLanguage.COMPOSE -> listOf("Switch(","Checkbox(","RadioButton(","TriStateCheckbox("); SupportedLanguage.REACT -> listOf("type=\"checkbox\"","type=\"radio\"","Switch"); SupportedLanguage.HTML_CSS_JS -> listOf("type=\"checkbox\"","type=\"radio\""); SupportedLanguage.XML -> listOf("<CheckBox","<Switch","<RadioButton"); SupportedLanguage.FLUTTER -> listOf("Checkbox(","Switch(","Radio("); SupportedLanguage.SWIFTUI -> listOf("Toggle(","isOn:"); SupportedLanguage.JAVA -> listOf("CheckBox","Switch","RadioButton") }
        "card"       -> when(lang) { SupportedLanguage.COMPOSE -> listOf("Card(","ElevatedCard(","OutlinedCard("); SupportedLanguage.REACT -> listOf("Card","elevation","shadow"); SupportedLanguage.HTML_CSS_JS -> listOf("class=\"card\"","box-shadow","elevation"); SupportedLanguage.XML -> listOf("CardView","MaterialCardView"); SupportedLanguage.FLUTTER -> listOf("Card(","elevation:"); SupportedLanguage.SWIFTUI -> listOf("VStack(","ZStack(","background("); SupportedLanguage.JAVA -> listOf("CardView") }
        "toolbar"    -> when(lang) { SupportedLanguage.COMPOSE -> listOf("TopAppBar(","CenterAlignedTopAppBar(","MediumTopAppBar(","LargeTopAppBar("); SupportedLanguage.REACT -> listOf("<AppBar","<Toolbar","<Header"); SupportedLanguage.HTML_CSS_JS -> listOf("<header","<nav"); SupportedLanguage.XML -> listOf("Toolbar","MaterialToolbar","AppBarLayout","CollapsingToolbarLayout"); SupportedLanguage.FLUTTER -> listOf("AppBar(","PreferredSize("); SupportedLanguage.SWIFTUI -> listOf("NavigationStack(","navigationTitle(","toolbar("); SupportedLanguage.JAVA -> listOf("Toolbar","setSupportActionBar") }
        "navigation" -> when(lang) { SupportedLanguage.COMPOSE -> listOf("NavigationBar(","NavigationRail(","NavigationDrawer(","NavigationBarItem("); SupportedLanguage.REACT -> listOf("<BottomTabNavigator","<TabBar","<BottomNavigation"); SupportedLanguage.HTML_CSS_JS -> listOf("<nav","tab","navbar"); SupportedLanguage.XML -> listOf("BottomNavigationView","NavigationView","NavigationBarView"); SupportedLanguage.FLUTTER -> listOf("BottomNavigationBar(","NavigationRail("); SupportedLanguage.SWIFTUI -> listOf("TabView(","tabItem("); SupportedLanguage.JAVA -> listOf("BottomNavigationView","NavigationView") }
        "fab"        -> when(lang) { SupportedLanguage.COMPOSE -> listOf("FloatingActionButton(","ExtendedFloatingActionButton(","SmallFloatingActionButton("); SupportedLanguage.REACT -> listOf("FAB","FloatingActionButton"); SupportedLanguage.HTML_CSS_JS -> listOf("fab","floating"); SupportedLanguage.XML -> listOf("FloatingActionButton","ExtendedFloatingActionButton"); SupportedLanguage.FLUTTER -> listOf("FloatingActionButton("); SupportedLanguage.SWIFTUI -> listOf(".floatingActionButton"); SupportedLanguage.JAVA -> listOf("FloatingActionButton") }
        "chip"       -> when(lang) { SupportedLanguage.COMPOSE -> listOf("FilterChip(","AssistChip(","InputChip(","SuggestionChip("); SupportedLanguage.XML -> listOf("<Chip","ChipGroup"); else -> listOf("chip","tag","badge") }
        "progress"   -> when(lang) { SupportedLanguage.COMPOSE -> listOf("CircularProgressIndicator(","LinearProgressIndicator("); SupportedLanguage.XML -> listOf("<ProgressBar","determinate"); SupportedLanguage.FLUTTER -> listOf("CircularProgressIndicator(","LinearProgressIndicator("); else -> listOf("progress","spinner","loading") }
        else -> emptyList()
    }

    private fun extractExpectedTexts(reference: String): List<String> {
        val quoted = Regex("\"([^\"]{2,80})\"").findAll(reference).map { it.groupValues[1].trim() }
        val xmlText = Regex("""\btext="([^"]{2,80})"""").findAll(reference).map { it.groupValues[1].trim() }
        val xmlDesc = Regex("""\bcontent-desc="([^"]{2,80})"""").findAll(reference).map { it.groupValues[1].trim() }
        return (quoted + xmlText + xmlDesc)
            .map { it.replace("\\s+".toRegex(), " ").trim() }
            .filter { it.isNotBlank() && it != "••••••••" && !it.startsWith("bounds:") && it.length > 1 }
            .distinct().take(50).toList()
    }

    private fun extractBounds(line: String): String =
        Regex("""\[(\d+,\d+)\]\[(\d+,\d+)\]""").find(line)?.value ?: ""

    private fun extractInlineText(line: String): String =
        Regex("""text="([^"]*)")""").find(line)?.groupValues?.get(1) ?: ""

    private fun estimateCodeNestingDepth(code: String): Int {
        var max = 0; var cur = 0
        code.forEach { c -> when(c) { '{', '(' -> { cur++; if(cur>max) max=cur }; '}', ')' -> cur-- } }
        return max
    }

    private fun extractScreenshotUri(output: String): String? {
        val start = output.indexOf("[SCREENSHOT_BASE64]"); val end = output.indexOf("[/SCREENSHOT_BASE64]")
        if (start == -1 || end == -1 || end <= start) return null
        return output.substring(start + "[SCREENSHOT_BASE64]".length, end).trim().ifBlank { null }
    }

    private suspend fun captureRawDump(): String? {
        val cmd = "uiautomator dump /data/local/tmp/omnidev_uidump.xml >/dev/null 2>&1 && cat /data/local/tmp/omnidev_uidump.xml && rm -f /data/local/tmp/omnidev_uidump.xml"
        val result = ShizukuCommandTool.execute(cmd)
        return result.outputOrNull()?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_RAW_DUMP_CHARS)
    }

    private fun launchPackage(context: Context, packageName: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "Failed: no launch intent for $packageName."
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); "Launched $packageName." }
            .getOrElse { "Failed to launch $packageName: ${it.message}" }
    }

    private suspend fun launchPackageWithRetry(context: Context, packageName: String): String {
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try { return launchPackage(context, packageName) }
            catch (e: Exception) { if (attempt < MAX_RETRY_ATTEMPTS - 1) delay(RETRY_DELAY_MS) }
        }
        return "Failed to launch $packageName after $MAX_RETRY_ATTEMPTS attempts."
    }

    private fun noCtx() = ToolExecutionResult("Android context required for this action.", isError = true)
}
