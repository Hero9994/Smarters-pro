package app.masahati.mobile

import org.json.JSONArray
import org.json.JSONObject

object DocumentIntelligence {
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
        if (asksName) {
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
            val dates = Regex("""\\b(?:0?[1-9]|[12]\\d|3[01])[./-](?:0?[1-9]|1[0-2])[./-](?:19|20)\\d{2}\\b""")
                .findAll(ocr).map { it.value }.distinct().toList()
            if (dates.isNotEmpty()) {
                val chosen = if (asksStart) dates.first() else dates.last()
                return result(
                    reply = if (asksStart) "تاريخ البداية الظاهر في الورقة: $chosen" else "تاريخ الانتهاء الظاهر في الورقة: $chosen",
                    summary = doc.summary.orEmpty().ifBlank { ocr.take(300) },
                    confidence = if (dates.size == 1) 0.88 else 0.72
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
