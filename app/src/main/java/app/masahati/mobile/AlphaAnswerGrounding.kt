package app.masahati.mobile

import org.json.JSONArray
import org.json.JSONObject

object AlphaAnswerGrounding {
    internal fun preferredFields(question: String): Set<String> {
        val q = question.lowercase()
        return when {
            listOf("ينتهي", "انتهاء", "صلاحية", "gültig", "ablauf", "vertragsende").any(q::contains) ->
                setOf("expiry_date")
            listOf("موعد", "آخر موعد", "ادفع", "دفع", "رد", "due", "frist", "zahlen").any(q::contains) ->
                setOf("due_date", "action_text")
            listOf("مبلغ", "كم", "سعر", "€", "eur", "betrag").any(q::contains) ->
                setOf("amount_text", "currency")
            listOf("رقم", "aktenzeichen", "kundennummer", "rechnungsnummer", "reference").any(q::contains) ->
                setOf("reference_number")
            listOf("شو لازم", "ماذا أفعل", "مطلوب", "اعمل", "action").any(q::contains) ->
                setOf("action_text", "due_date")
            else -> emptySet()
        }
    }

    fun apply(result: JSONObject, question: String, document: MessageRow?, db: MasahatiDatabase) {
        if (document == null) return
        val classification = result.optString("classification")
        if (classification != "document" && !DocumentIntelligence.isDocumentQuestion(question)) return

        val meta = db.getDocumentMeta(document.id)
        val evidence = meta?.evidenceJson?.let { runCatching { JSONArray(it) }.getOrNull() }
        val selected = selectEvidence(evidence, preferredFields(question))
        val confidence = result.optDouble("confidence", meta?.confidence ?: 0.72)
            .takeIf { !it.isNaN() } ?: (meta?.confidence ?: 0.72)

        var reply = result.optString("reply").trim()
        if (confidence < 0.40 && selected.isNullOrBlank()) {
            reply = "ما عندي دليل كافي في النص المقروء حتى أعطيك جواب موثوق. إذا بدك، افتح المستند أو أعد مسحه بجودة أوضح."
        } else {
            if (!selected.isNullOrBlank() && !reply.contains("الدليل:", ignoreCase = true)) {
                reply += "\n\nالدليل: «${selected.take(650)}»"
            }
            if (confidence < 0.65 && !reply.contains("الثقة", ignoreCase = true)) {
                reply += "\n\nالثقة منخفضة (${(confidence.coerceIn(0.0, 1.0) * 100).toInt()}%)، لذلك لا أعتبر هذه المعلومة مؤكدة."
            }
        }
        result.put("reply", reply.take(2200))
    }

    private fun selectEvidence(evidence: JSONArray?, preferred: Set<String>): String? {
        if (evidence == null || evidence.length() == 0) return null
        if (preferred.isNotEmpty()) {
            for (i in 0 until evidence.length()) {
                val item = evidence.optJSONObject(i) ?: continue
                if (item.optString("field") in preferred) {
                    item.optString("excerpt").trim().takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        for (i in 0 until evidence.length()) {
            val excerpt = evidence.optJSONObject(i)?.optString("excerpt")?.trim().orEmpty()
            if (excerpt.isNotBlank()) return excerpt
        }
        return null
    }
}
