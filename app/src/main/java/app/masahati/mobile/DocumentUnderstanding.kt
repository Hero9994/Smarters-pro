package app.masahati.mobile

import org.json.JSONObject

/** Presentation of the evidence-checked document schema; no cloud request or data movement. */
object DocumentUnderstanding {
    fun details(doc: JSONObject): List<String> = buildList {
        add("حالة الفهم: " + when (doc.optString("analysis_status")) {
            "analyzed" -> "تحليل للمحتوى مع شواهد من النص"
            "needs_review" -> "يحتاج مراجعتك"
            "limited" -> "قراءة أولية؛ الفهم المتقدم غير متاح"
            else -> "النص غير مقروء بما يكفي"
        })
        for ((key, label) in listOf("smart_title" to "الاسم", "doc_type_label" to "النوع", "topic_label" to "الموضوع", "organization" to "الجهة")) {
            doc.optString(key).takeIf { it.isNotBlank() }?.let { add("$label: $it") }
        }
        doc.optJSONArray("person_names")?.let { names ->
            if (names.length() > 0) add("الأسماء: " + (0 until names.length()).joinToString("، ") { names.optString(it) })
        }
        for (key in listOf("references", "amounts", "dates")) {
            val arr = doc.optJSONArray(key) ?: continue
            for (i in 0 until arr.length().coerceAtMost(16)) {
                val fact = arr.optJSONObject(i) ?: continue
                val label = fact.optString("label").ifBlank { "رقم مرجعي" }
                add("$label: ${fact.optString("value")}")
            }
        }
        val action = doc.optString("action_text")
        if (action.isNotBlank()) add("الإجراء بحسب النص (${when (doc.optString("action_status")) {
            "required" -> "مطلوب"
            "none" -> "غير مطلوب في هذا المقطع"
            "possible" -> "اختياري أو مشروط"
            else -> "غير مؤكد"
        }}): $action")
        doc.optJSONArray("review_reasons")?.let { reasons ->
            for (i in 0 until reasons.length()) add("مراجعة: ${reasons.optString(i)}")
        }
        // Numeric confidence is an uncalibrated estimate, not an accuracy percentage.
        doc.optJSONArray("evidence")?.let { evidence ->
            val quotes = (0 until evidence.length()).mapNotNull { evidence.optJSONObject(it)?.optString("excerpt") }
                .filter { it.isNotBlank() }.distinct().take(10)
            if (quotes.isNotEmpty()) add("\nشواهد من الورقة:\n" + quotes.joinToString("\n") { "• $it" })
        }
    }

    fun hasGroundedAction(doc: JSONObject, ocr: String): Boolean {
        if (doc.optInt("schema_version", 0) < 3 || !doc.optBoolean("action_required", false)) return false
        if (doc.optString("action_status") != "required") return false
        val text = normalize(doc.optString("action_text"))
        if (text.isBlank() || !normalize(ocr).contains(text)) return false
        val evidence = doc.optJSONArray("evidence") ?: return false
        return (0 until evidence.length()).any {
            val fact = evidence.optJSONObject(it)
            fact?.optString("field") == "action_text" && normalize(fact.optString("excerpt")) == text
        }
    }

    private fun normalize(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), " ").trim().lowercase(java.util.Locale.ROOT)
}
