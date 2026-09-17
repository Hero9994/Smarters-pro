package app.masahati.mobile

/** Reading diagnostics stay separate from document text, so warnings are never indexed as facts. */
data class DocumentReadResult(
    val text: String,
    val note: String? = null,
    val totalPages: Int = 1,
    val processedPages: Int = 1,
    val ocrPages: Int = 0
)

internal object DocumentTextMerge {
    fun useful(text: String): Boolean = text.count(Char::isLetterOrDigit) >= 3 &&
        text.count { it == '\uFFFD' } * 5 <= text.length

    fun merge(embedded: String, recognized: String): String {
        if (!useful(embedded)) return recognized.trim()
        if (!useful(recognized)) return embedded.trim()
        fun key(line: String) = line.lowercase().filter(Char::isLetterOrDigit)
        val existing = embedded.lines().map(::key).filter(String::isNotEmpty).toMutableSet()
        val extra = recognized.lines().filter { line ->
            val normalized = key(line)
            normalized.length >= 3 && existing.add(normalized) &&
                !existing.any { it != normalized && it.contains(normalized) }
        }
        return (listOf(embedded.trim()) + extra).joinToString("\n").trim()
    }
}
