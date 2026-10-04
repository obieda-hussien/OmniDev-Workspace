package com.omnidev.workspace.data.voice

/** Ephemeral credential values. Never serialize, log, display, or send to a model. */
class SpokenCredential(val kind: Kind, val chars: CharArray) : AutoCloseable {
    enum class Kind { PIN, PASSWORD, PATTERN }
    override fun close() { chars.fill('\u0000') }
    override fun toString() = "SpokenCredential(REDACTED)"
}

object SpokenCredentialParser {
    private val digits = mapOf("zero" to '0', "oh" to '0', "صفر" to '0', "one" to '1', "واحد" to '1', "two" to '2', "اثنين" to '2', "اتنين" to '2',
        "three" to '3', "ثلاثه" to '3', "تلاته" to '3', "four" to '4', "اربعه" to '4', "five" to '5', "خمسه" to '5',
        "six" to '6', "سته" to '6', "seven" to '7', "سبعه" to '7', "eight" to '8', "ثمانيه" to '8', "تمانيه" to '8', "nine" to '9', "تسعه" to '9')
    private val symbols = mapOf("at" to '@', "hash" to '#', "dollar" to '$', "percent" to '%', "star" to '*', "underscore" to '_', "dash" to '-',
        "plus" to '+', "equals" to '=', "dot" to '.', "comma" to ',', "slash" to '/', "backslash" to '\\', "colon" to ':', "semicolon" to ';',
        "question" to '?', "exclamation" to '!', "ampersand" to '&', "space" to ' ', "quote" to '\'', "doublequote" to '"', "pipe" to '|',
        "tilde" to '~', "caret" to '^', "backtick" to '`', "leftparen" to '(', "rightparen" to ')', "leftbracket" to '[', "rightbracket" to ']', "leftbrace" to '{', "rightbrace" to '}')
    private val letters = mapOf("alpha" to 'a', "bravo" to 'b', "charlie" to 'c', "delta" to 'd', "echo" to 'e', "foxtrot" to 'f', "golf" to 'g',
        "hotel" to 'h', "india" to 'i', "juliet" to 'j', "kilo" to 'k', "lima" to 'l', "mike" to 'm', "november" to 'n', "oscar" to 'o',
        "papa" to 'p', "quebec" to 'q', "romeo" to 'r', "sierra" to 's', "tango" to 't', "uniform" to 'u', "victor" to 'v', "whiskey" to 'w', "xray" to 'x', "yankee" to 'y', "zulu" to 'z')
    private val arabic = mapOf("الف" to 'ا', "باء" to 'ب', "تاء" to 'ت', "ثاء" to 'ث', "جيم" to 'ج', "حاء" to 'ح', "خاء" to 'خ', "دال" to 'د', "ذال" to 'ذ',
        "راء" to 'ر', "زاي" to 'ز', "سين" to 'س', "شين" to 'ش', "صاد" to 'ص', "ضاد" to 'ض', "طاء" to 'ط', "ظاء" to 'ظ', "عين" to 'ع', "غين" to 'غ',
        "فاء" to 'ف', "قاف" to 'ق', "كاف" to 'ك', "لام" to 'ل', "ميم" to 'م', "نون" to 'ن', "هاء" to 'ه', "واو" to 'و', "ياء" to 'ي')
    private fun normalize(value: String) = value.lowercase(java.util.Locale.ROOT).replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه')
    private fun digit(char: Char): Char? = when (char) { in '0'..'9' -> char; in '٠'..'٩' -> '0' + (char - '٠'); in '۰'..'۹' -> '0' + (char - '۰'); else -> null }
    fun parse(text: String, kind: SpokenCredential.Kind): SpokenCredential {
        require(text.length in 1..512) { "Could not understand the credential. Try manual unlock." }
        val tokens = text.trim().split(Regex("\\s+"))
        val output = CharArray(64); var size = 0
        fun append(c: Char) { require(size < output.size); output[size++] = c }
        try {
            var index = 0
            while (index < tokens.size) {
                var token = normalize(tokens[index++])
                if (kind != SpokenCredential.Kind.PASSWORD) {
                    val spoken = digits[token]
                    if (spoken != null) append(spoken)
                    else { require(token.all { digit(it) != null }); token.forEach { append(digit(it)!!) } }
                    continue
                }
                var upper = false
                if (token in setOf("capital", "uppercase", "كابيتال")) { upper = true; require(index < tokens.size); token = normalize(tokens[index++]) }
                if (token in setOf("lowercase", "small")) { require(!upper && index < tokens.size); token = normalize(tokens[index++]) }
                when {
                    token in setOf("literal", "كلمه") -> {
                        require(!upper && index < tokens.size)
                        val word = tokens[index++]
                        require(word.length in 1..24 && word.all { it.isLetterOrDigit() })
                        word.forEach(::append)
                    }
                    token in setOf("arabic", "عربي") -> { require(!upper && index < tokens.size); append(arabic[normalize(tokens[index++])] ?: error("Spell the Arabic letter explicitly.")) }
                    token in digits -> { require(!upper); append(digits.getValue(token)) }
                    token in symbols -> { require(!upper); append(symbols.getValue(token)) }
                    token in letters -> append(if (upper) letters.getValue(token).uppercaseChar() else letters.getValue(token))
                    token.length == 1 && token[0].isLetter() -> append(if (upper) token[0].uppercaseChar() else token[0])
                    token.all { digit(it) != null } -> { require(!upper); token.forEach { append(digit(it)!!) } }
                    else -> error("Spell letters, capitalization and symbols explicitly; no guessed password text.")
                }
            }
            when (kind) {
                SpokenCredential.Kind.PIN -> require(size in 4..16)
                SpokenCredential.Kind.PASSWORD -> require(size in 4..64)
                SpokenCredential.Kind.PATTERN -> {
                    val expanded = PatternSequence.expand(output.copyOf(size))
                    require(expanded.size in 4..9)
                    return SpokenCredential(kind, expanded)
                }
            }
            return SpokenCredential(kind, output.copyOf(size))
        } finally { output.fill('\u0000') }
    }
}

/** Android 3×3 numbering: 1–3 top, 4–6 middle, 7–9 bottom; include skipped midpoints. */
object PatternSequence {
    fun expand(input: CharArray): CharArray {
        try {
            require(input.size in 2..9 && input.all { it in '1'..'9' })
            val result = ArrayList<Char>(9)
            for (next in input) {
                require(next !in result) { "Pattern repeats a selected point." }
                val previous = result.lastOrNull()
                if (previous != null) {
                    val a = previous - '1'; val b = next - '1'
                    val dr = b / 3 - a / 3; val dc = b % 3 - a % 3
                    if ((kotlin.math.abs(dr) == 2 && dc == 0) || (kotlin.math.abs(dc) == 2 && dr == 0) || (kotlin.math.abs(dr) == 2 && kotlin.math.abs(dc) == 2)) {
                        val middle = '1' + ((a / 3 + b / 3) / 2 * 3 + (a % 3 + b % 3) / 2)
                        if (middle !in result) result += middle
                    }
                }
                result += next
            }
            return result.toCharArray()
        } finally { input.fill('\u0000') }
    }
}
