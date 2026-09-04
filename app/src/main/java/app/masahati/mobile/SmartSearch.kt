package app.masahati.mobile

import java.util.Locale
import kotlin.math.abs

internal object SmartSearch {
    private val arabicMarks = Regex("[\\u064B-\\u065F\\u0670]")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val spaces = Regex("\\s+")

    fun normalize(value: String): String {
        if (value.isBlank()) return ""
        val folded = buildString(value.length) {
            value.lowercase(Locale.ROOT)
                .replace("ـ", "")
                .replace(arabicMarks, "")
                .forEach { ch ->
                    append(
                        when (ch) {
                            'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                            'ى' -> 'ي'
                            'ؤ' -> 'و'
                            'ئ' -> 'ي'
                            else -> ch
                        }
                    )
                }
        }
        return folded.replace(separators, " ").replace(spaces, " ").trim()
    }

    fun queryTerms(query: String): List<String> {
        val normalized = normalize(query)
        if (normalized.isBlank()) return emptyList()
        val out = linkedSetOf<String>()
        normalized.split(' ').filter { it.length >= 2 }.forEach { token ->
            out += token
            if (token.startsWith("ال") && token.length > 4) out += token.drop(2)
        }
        return out.take(10)
    }

    fun score(
        query: String,
        displayName: String?,
        tags: String?,
        classification: String?,
        summary: String?,
        text: String?,
        ocrText: String?
    ): Int {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return 0
        val terms = queryTerms(normalizedQuery)
        if (terms.isEmpty()) return 0
        val fields = listOf(
            Field(displayName, 130, 34),
            Field(tags, 105, 30),
            Field(classification, 90, 26),
            Field(summary, 82, 22),
            Field(text, 72, 18),
            Field(ocrText, 62, 15)
        )
        var total = 0
        fields.forEach { field ->
            val normalized = normalize(field.value.orEmpty())
            if (normalized.isBlank()) return@forEach
            if (normalized.contains(normalizedQuery)) total += field.exactWeight
            val words = normalized.split(' ').filter { it.length >= 2 }
            terms.forEach { term ->
                when {
                    normalized.contains(term) -> total += field.termWeight
                    term.length >= 4 && words.any { nearMatch(term, it) } ->
                        total += (field.termWeight / 3).coerceAtLeast(2)
                }
            }
        }
        return total
    }

    private fun nearMatch(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 1) return false
        if (a == b) return true
        if (a.length == b.length) {
            var differences = 0
            for (i in a.indices) {
                if (a[i] != b[i] && ++differences > 1) return false
            }
            return true
        }
        val shorter = if (a.length < b.length) a else b
        val longer = if (a.length < b.length) b else a
        var i = 0
        var j = 0
        var skipped = false
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++
                j++
            } else {
                if (skipped) return false
                skipped = true
                j++
            }
        }
        return true
    }

    private data class Field(val value: String?, val exactWeight: Int, val termWeight: Int)
}
