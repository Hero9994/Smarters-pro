package app.masahati.mobile

import org.json.JSONArray
import org.json.JSONObject

object DocumentIntelligence {
    private val DATE_REGEX = Regex("""\b(?:0?[1-9]|[12]\d|3[01])[./-](?:0?[1-9]|1[0-2])[./-](?:19|20)\d{2}\b""")

    fun isDocumentQuestion(text: String): Boolean {
        val q = text.trim().lowercase()
        return listOf(
            "شو فيها", "شو فيه", "شو مكتوب", "شو هاد", "شو هاي", "هاد شو", "هاي شو",
            "محتوى الورقة", "محتوى المستند", "شو محتواها", "شو محتواه",
            "شو سميتها", "شو اسمه", "شو اسمها", "اسم الورقة", "اسم الملف",
            "متى", "تاريخ", "ينتهي", "تنتهي", "انتهاء", "بداية",
            "wer", "was ist", "worum", "datum", "ende", "beginn", "name"
        ).any(q::contains)
    }

    fun directAnswer(question: String, doc: MessageRow?): JSONObject? {
        if (doc == null) return null
        val q = question.trim().lowercase()
        val asksName = listOf(
            "شو سميتها", "شو اسمها", "شو اسمه", "اسم الورقة", "اسم الملف", "dateiname", "filename"
        ).any(q::contains)
        val asksContent = listOf(
            "شو فيها", "شو فيه", "شو مكتوب", "محتوى", "شو محتواها", "شو محتواه",
            "شو هاد", "شو هاي", "هاد شو", "هاي شو", "was ist", "worum"
        ).any(q::contains)
        if (asksName && !asksContent) {
            val name = doc.displayName.orEmpty().ifBlank { "الملف غير مُسمّى" }
            return result(
                reply = "اسم الملف: $name",
                summary = doc.summary.orEmpty().ifBlank { name },
                confidence = 1.0
            )
        }

        val ocr = doc.ocrText.orEmpty().trim()
        if (isDocumentQuestion(question) && ocr.isBlank()) {
            return result(
                reply = "أنا شايف الملف «${doc.displayName ?: "المستند"}»، لكن القراءة النصية OCR لم تستخرج منه نصاً واضحاً. لذلك لن أخمّن محتواه.",
                summary = "تعذر استخراج نص واضح من المستند.",
                confidence = 0.99
            )
        }

        val asksEnd = listOf("ينتهي", "تنتهي", "انتهاء", "نهاية", "ende", "ablauf", "gültig bis", "vertragsende").any(q::contains)
        val asksStart = listOf("يبدأ", "يبدا", "بداية", "beginn", "startdatum", "vertragsbeginn").any(q::contains)
        if ((asksEnd || asksStart) && ocr.isNotBlank()) {
            val dates = DATE_REGEX.findAll(ocr).map { it.value }.distinct().toList()
            val keywords = if (asksStart) {
                listOf("vertragsbeginn", "beginn", "startdatum", "gültig ab", "gueltig ab", "بداية", "يبدأ", "يبدا")
            } else {
                listOf("vertragsende", "gültig bis", "gueltig bis", "ablauf", "ende", "endet", "انتهاء", "ينتهي", "تنتهي")
            }
            val chosen = findDateNear(ocr, keywords) ?: dates.singleOrNull()
            if (chosen != null) {
                return result(
                    reply = if (asksStart) "تاريخ البداية الظاهر في الورقة: $chosen" else "تاريخ الانتهاء الظاهر في الورقة: $chosen",
                    summary = doc.summary.orEmpty().ifBlank { ocr.take(300) },
                    confidence = 0.9
                )
            }
        }
        return null
    }

    fun blankScanResult(doc: MessageRow): JSONObject? {
        if (!doc.ocrText.isNullOrBlank()) return null
        return result(
            reply = "حفظت المستند باسم «${doc.displayName ?: "ملف"}»، لكن OCR لم يلتقط نصاً مقروءاً منه. ما رح أخمّن المحتوى.",
            summary = "مستند محفوظ لكن النص غير مقروء آلياً.",
            confidence = 0.99
        )
    }

    fun offlineDocumentFallback(question: String, doc: MessageRow?): JSONObject? {
        if (doc == null || !isDocumentQuestion(question)) return null
        directAnswer(question, doc)?.let { return it }
        val ocr = doc.ocrText.orEmpty().trim()
        if (ocr.isBlank()) return blankScanResult(doc)
        val excerpt = compactMeaningful(ocr)
        return result(
            reply = "من النص المقروء في «${doc.displayName ?: "المستند"}»:\n$excerpt",
            summary = excerpt.take(420),
            confidence = 0.58
        )
    }

    private fun findDateNear(text: String, keywords: List<String>): String? {
        val lower = text.lowercase()
        for (keyword in keywords) {
            var from = 0
            while (true) {
                val index = lower.indexOf(keyword.lowercase(), from)
                if (index < 0) break
                val start = (index - 40).coerceAtLeast(0)
                val end = (index + keyword.length + 100).coerceAtMost(text.length)
                DATE_REGEX.find(text.substring(start, end))?.value?.let { return it }
                from = index + keyword.length
            }
        }
        return null
    }

    private fun compactMeaningful(ocr: String): String {
        val lines = ocr.lines()
            .map { it.trim() }
            .filter { it.length >= 3 && !it.matches(Regex("""صفحة\\s+\\d+:""")) }
        return lines.take(8).joinToString("\\n").take(900).ifBlank { ocr.take(900) }
    }

    private fun result(reply: String, summary: String, confidence: Double): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("engine", "document-intelligence")
            .put("classification", "document")
            .put("labels", JSONArray(listOf("مستند")))
            .put("keywords", JSONArray())
            .put("summary", summary.take(600))
            .put("confidence", confidence)
            .put("actions", JSONArray())
            .put("reply", reply)
}
