package app.masahati.mobile

import java.util.Locale
import org.apache.commons.text.similarity.JaroWinklerSimilarity
import org.apache.commons.text.similarity.LevenshteinDistance
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

    private val levenshteinOne = LevenshteinDistance(1)
    private val jaroWinkler = JaroWinklerSimilarity()

    private fun nearMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (abs(a.length - b.length) > 2) return false
        if (levenshteinOne.apply(a, b) in 0..1) return true
        if (a.length >= 5 && b.length >= 5) {
            return (jaroWinkler.apply(a, b) ?: 0.0) >= 0.93
        }
        return false
    }

    private data class Field(val value: String?, val exactWeight: Int, val termWeight: Int)
}
