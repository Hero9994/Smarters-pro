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

    internal fun asksOnlyForName(text: String): Boolean {
        val q = text.trim().lowercase()
        val asksName = listOf(
            "شو سميتها", "شو اسمها", "شو اسمه", "اسم الورقة", "اسم الملف", "dateiname", "filename"
        ).any(q::contains)
        val asksContent = listOf(
            "شو فيها", "شو فيه", "شو مكتوب", "محتوى", "شو محتواها", "شو محتواه",
            "شو هاد", "شو هاي", "هاد شو", "هاي شو", "was ist", "worum"
        ).any(q::contains)
        return asksName && !asksContent
    }

    fun knownDocumentResult(doc: MessageRow): JSONObject? {
        val ocr = doc.ocrText.orEmpty().trim()
        if (ocr.isBlank()) return null
        val lower = ocr.lowercase()

        if (
            listOf("krankenbeförderung", "krankenbefoerderung", "krankentransport").any(lower::contains) &&
            listOf("arzt", "ärzt", "wohnung", "wohnort", "patient").any(lower::contains)
        ) {
            return result(
                reply = "قرأت المستند: هو موافقة/تصريح مرتبط بنقل المريض من المنزل إلى الطبيب.",
                summary = "موافقة/تصريح لنقل المريض من المنزل إلى الطبيب",
                confidence = 0.98,
                labels = listOf("مستند", "نقل مرضى", "طبيب"),
                keywords = listOf("Krankenbeförderung", "نقل مرضى", "طبيب", "Wohnort", "Arzt")
            )
        }

        if (lower.contains("mietvertrag")) {
            val start = findDateNear(ocr, listOf("vertragsbeginn", "beginn", "gültig ab", "gueltig ab"))
            val end = findDateNear(ocr, listOf("vertragsende", "gültig bis", "gueltig bis", "ablauf", "endet"))
            val dateText = listOfNotNull(
                start?.let { "البداية $it" },
                end?.let { "الانتهاء $it" }
            ).joinToString("، ")
            val summary = if (dateText.isBlank()) "عقد إيجار" else "عقد إيجار: $dateText"
            return result(
                reply = "قرأت المستند كعقد إيجار" + if (dateText.isBlank()) "." else "، $dateText.",
                summary = summary,
                confidence = 0.96,
                labels = listOf("مستند", "عقد إيجار"),
                keywords = listOfNotNull("Mietvertrag", "عقد إيجار", start, end)
            )
        }

        if (lower.contains("rechnung") || lower.contains("invoice")) {
            return result(
                reply = "قرأت المستند كفاتورة وحفظت نوعه للبحث.",
                summary = "فاتورة",
                confidence = 0.9,
                labels = listOf("مستند", "فاتورة"),
                keywords = listOf("Rechnung", "فاتورة")
            )
        }

        if (lower.contains("aok") || lower.contains("krankenversicherung")) {
            val contribution = lower.contains("beitrag") || lower.contains("beitragsbescheinigung")
            val summary = if (contribution) "مستند تأمين صحي من AOK متعلق بالمساهمات/الاشتراكات" else "مستند تأمين صحي من AOK"
            return result(
                reply = "قرأت المستند كمستند تأمين صحي من AOK" + if (contribution) " متعلق بالمساهمات أو الاشتراكات." else ".",
                summary = summary,
                confidence = 0.9,
                labels = listOf("مستند", "AOK", "تأمين صحي"),
                keywords = listOf("AOK", "Krankenversicherung", "تأمين صحي")
            )
        }

        if (lower.contains("bescheid")) {
            return result(
                reply = "قرأت المستند كقرار أو إشعار رسمي (Bescheid)، وسأحفظ هذا النوع للبحث.",
                summary = "قرار/إشعار رسمي (Bescheid)",
                confidence = 0.86,
                labels = listOf("مستند", "Bescheid", "قرار رسمي"),
                keywords = listOf("Bescheid", "قرار رسمي")
            )
        }

        return null
    }

    fun directAnswer(question: String, doc: MessageRow?): JSONObject? {
        if (doc == null) return null
        val q = question.trim().lowercase()
        if (asksOnlyForName(question)) {
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

        val groundedDate = resolveGroundedDate(question, ocr)
        if (groundedDate != null) {
            return result(
                reply = if (groundedDate.first == "start") {
                    "تاريخ البداية الظاهر في الورقة: ${groundedDate.second}"
                } else {
                    "تاريخ الانتهاء الظاهر في الورقة: ${groundedDate.second}"
                },
                summary = doc.summary.orEmpty().ifBlank { ocr.take(300) },
                confidence = 0.9
            )
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

    internal fun resolveGroundedDate(question: String, ocr: String): Pair<String, String>? {
        if (ocr.isBlank()) return null
        val q = question.trim().lowercase()
        val asksEnd = listOf(
            "ينتهي", "تنتهي", "انتهاء", "نهاية", "ende", "ablauf", "gültig bis", "gueltig bis", "vertragsende"
        ).any(q::contains)
        val asksStart = listOf(
            "يبدأ", "يبدا", "بداية", "beginn", "startdatum", "vertragsbeginn", "gültig ab", "gueltig ab"
        ).any(q::contains)
        if (!asksEnd && !asksStart) return null

        val dates = DATE_REGEX.findAll(ocr).map { it.value }.distinct().toList()
        val keywords = if (asksStart) {
            listOf("vertragsbeginn", "beginn", "startdatum", "gültig ab", "gueltig ab", "بداية", "يبدأ", "يبدا")
        } else {
            listOf("vertragsende", "gültig bis", "gueltig bis", "ablauf", "ende", "endet", "انتهاء", "ينتهي", "تنتهي")
        }
        val chosen = findDateNear(ocr, keywords) ?: dates.singleOrNull() ?: return null
        return (if (asksStart) "start" else "end") to chosen
    }

    private fun findDateNear(text: String, keywords: List<String>): String? {
        val lower = text.lowercase()
        for (keyword in keywords) {
            var from = 0
            while (true) {
                val index = lower.indexOf(keyword.lowercase(), from)
                if (index < 0) break
                val afterStart = (index + keyword.length).coerceAtMost(text.length)
                val afterEnd = (afterStart + 100).coerceAtMost(text.length)
                DATE_REGEX.find(text.substring(afterStart, afterEnd))?.value?.let { return it }

                val beforeStart = (index - 60).coerceAtLeast(0)
                val beforeDates = DATE_REGEX.findAll(text.substring(beforeStart, index)).map { it.value }.toList()
                beforeDates.lastOrNull()?.let { return it }
                from = index + keyword.length
            }
        }
        return null
    }

    private fun compactMeaningful(ocr: String): String {
        val lines = ocr.lines()
            .map { it.trim() }
            .filter { it.length >= 3 && !it.matches(Regex("""صفحة\s+\d+:""")) }
        return lines.take(8).joinToString("\n").take(900).ifBlank { ocr.take(900) }
    }

    private fun result(
        reply: String,
        summary: String,
        confidence: Double,
        labels: List<String> = listOf("مستند"),
        keywords: List<String> = emptyList()
    ): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("engine", "document-intelligence")
            .put("classification", "document")
            .put("labels", JSONArray(labels.distinct().take(8)))
            .put("keywords", JSONArray(keywords.filter { it.isNotBlank() }.distinct().take(12)))
            .put("summary", summary.take(600))
            .put("confidence", confidence)
            .put("actions", JSONArray())
            .put("reply", reply)
}
