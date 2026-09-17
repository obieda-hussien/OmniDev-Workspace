package com.omnidev.workspace.data.tools

import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object SearchSemantics {
    private val tokenSplit = Regex("[^a-z0-9\\u0600-\\u06FF]+")
    private val stop = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "of", "in", "to", "for", "on", "with", "at", "by", "from", "and", "or", "but", "about", "this", "that", "it", "as", "not", "using",
        "من", "في", "على", "عن", "إلى", "الى", "ما", "ماذا", "هو", "هي", "و", "او", "أو", "مع"
    )
    private val freshnessTerms = setOf(
        "latest", "recent", "newest", "new", "today", "current", "updated",
        "احدث", "أحدث", "حديث", "حديثة", "الجديد", "اليوم", "حاليا", "حالياً", "اخر", "آخر"
    )
    private val researchTerms = setOf(
        "research", "paper", "papers", "study", "studies", "academic", "scientific", "science", "preprint", "journal", "publication", "arxiv", "pubmed", "benchmark", "dataset", "algorithm", "method", "model", "models", "training", "inference", "neural", "clinical", "medical", "biology", "physics", "chemistry", "mathematics", "machine", "learning", "llm", "ai", "security", "software", "engineering",
        "بحث", "ابحاث", "أبحاث", "ورقة", "اوراق", "أوراق", "دراسة", "دراسات", "علمي", "علمية", "اكاديمي", "أكاديمي", "خوارزمية", "خوارزميات", "موديل", "موديلات", "نموذج", "نماذج", "تدريب", "استدلال", "ذكاء", "اصطناعي", "طبي", "طبية", "برمجة", "امن", "أمن"
    )
    private val tracking = setOf("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid", "ref", "ref_src", "ved", "ei", "sxsrf")
    private val doi = Regex("(?i)10\\.\\d{4,9}/[-._;()/:A-Z0-9]+")
    private val arxiv = Regex("(?i)(?:arxiv:|arxiv\\.org/(?:abs|pdf)/)(\\d{4}\\.\\d{4,5})(?:v\\d+)?")
    private val iso = Regex("\\b(20\\d{2})[-/.](0?[1-9]|1[0-2])[-/.](0?[1-9]|[12]\\d|3[01])\\b")
    private val dmy = Regex("(?i)\\b(0?[1-9]|[12]\\d|3[01])\\s+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s*,?\\s*(20\\d{2})\\b")
    private val mdy = Regex("(?i)\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+(0?[1-9]|[12]\\d|3[01])(?:st|nd|rd|th)?[,]?\\s+(20\\d{2})\\b")
    private val ymdText = Regex("(?i)\\b(20\\d{2})\\s+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)(?:\\s+(0?[1-9]|[12]\\d|3[01]))?\\b")
    private val relative = Regex("(?i)\\b(\\d{1,3})\\s+(minute|minutes|hour|hours|day|days|week|weeks|month|months)\\s+ago\\b")
    private val year = Regex("\\b(20\\d{2})\\b")

    fun tokenize(text: String): List<String> = text.lowercase(Locale.ROOT).split(tokenSplit).filter { it.length > 1 && it !in stop }

    fun isFreshnessIntent(query: String): Boolean = tokenize(query).any { it in freshnessTerms }

    fun isResearchIntent(query: String): Boolean = tokenize(query).any { it in researchTerms } ||
        query.contains("peer reviewed", true) || query.contains("state of the art", true) || query.contains("state-of-the-art", true)

    fun shouldSearchScholarly(query: String, mode: WebSearchMode): Boolean {
        if (isResearchIntent(query)) return true
        if (mode != WebSearchMode.DEEP || !isFreshnessIntent(query)) return false
        val technical = setOf("ai", "llm", "model", "algorithm", "medical", "science", "software", "ذكاء", "خوارزمية", "طبي")
        return tokenize(query).any { it in technical }
    }

    fun canonicalizeUrl(raw: String): String = try {
        val u = URI(raw.trim())
        val scheme = (u.scheme ?: "https").lowercase(Locale.ROOT)
        val host = (u.host ?: raw.substringBefore('#')).lowercase(Locale.ROOT).removePrefix("www.")
        val port = if ((scheme == "https" && u.port == 443) || (scheme == "http" && u.port == 80)) -1 else u.port
        val path = (u.rawPath ?: "/").replace(Regex("/{2,}"), "/").let { if (it.length > 1) it.trimEnd('/') else it }
        val q = u.rawQuery?.split('&')?.mapNotNull { p ->
            val rawKey = p.substringBefore('=')
            val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey).lowercase(Locale.ROOT)
            if (key.startsWith("utm_") || key in tracking) null else p
        }?.sorted()?.joinToString("&")?.takeIf { it.isNotBlank() }
        URI(scheme, null, host, port, path.ifBlank { "/" }, q, null).toASCIIString()
    } catch (_: Exception) { raw.substringBefore('#').trimEnd('/') }

    fun dedupKey(hit: WebSearchHit): String {
        val text = "${hit.url} ${hit.title} ${hit.snippet}"
        doi.find(text)?.value?.lowercase(Locale.ROOT)?.let { return "doi:$it" }
        arxiv.find(text)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)?.let { return "arxiv:$it" }
        return "url:${canonicalizeUrl(hit.url)}"
    }

    fun parseDate(text: String, now: Long = System.currentTimeMillis()): Long? {
        val s = text.replace('\u00A0', ' ')
        relative.find(s)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@let
            val ms = when {
                m.groupValues[2].startsWith("minute", true) -> 60_000L
                m.groupValues[2].startsWith("hour", true) -> 3_600_000L
                m.groupValues[2].startsWith("day", true) -> 86_400_000L
                m.groupValues[2].startsWith("week", true) -> 604_800_000L
                else -> 2_592_000_000L
            }
            return now - n * ms
        }
        iso.find(s)?.let { return utc(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        dmy.find(s)?.let { return utc(it.groupValues[3].toInt(), month(it.groupValues[2]) ?: return@let, it.groupValues[1].toInt()) }
        mdy.find(s)?.let { return utc(it.groupValues[3].toInt(), month(it.groupValues[1]) ?: return@let, it.groupValues[2].toInt()) }
        ymdText.find(s)?.let { return utc(it.groupValues[1].toInt(), month(it.groupValues[2]) ?: return@let, it.groupValues.getOrNull(3)?.toIntOrNull() ?: 1) }
        arxiv.find(s)?.groupValues?.getOrNull(1)?.let { id -> if (id.length >= 4) return utc(2000 + id.substring(0, 2).toInt(), id.substring(2, 4).toInt(), 1) }
        year.find(s)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return utc(it, 1, 1) }
        return null
    }

    fun formatDate(epoch: Long?): String? = epoch?.let {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(it))
    }

    private fun month(s: String): Int? = when (s.lowercase(Locale.ROOT).take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; "dec" -> 12
        else -> null
    }

    private fun utc(y: Int, m: Int, d: Int): Long? = try {
        Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            isLenient = false; clear(); set(y, m - 1, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    } catch (_: Exception) { null }
}
