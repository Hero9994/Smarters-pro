package app.masahati.mobile

import org.json.JSONArray
import org.json.JSONObject

object AgentResultSanitizer {
    private val allowedClasses = setOf(
        "note", "task", "reminder", "work_schedule", "document",
        "idea", "personal", "search", "command", "other"
    )
    private val allowedActions = setOf(
        "enrich_previous_document", "search", "archive_space", "pin_space",
        "rename_space", "move_last_item", "create_space",
        "rename_last_document", "move_last_document"
    )

    fun parse(raw: String, userText: String): JSONObject? {
        val parsed = extractJson(raw) ?: return null
        return sanitize(parsed, userText)
    }

    fun sanitize(parsed: JSONObject, userText: String): JSONObject {
        val classification = parsed.optString("classification")
            .takeIf { it in allowedClasses } ?: "other"
        val summary = parsed.optString("summary").trim().take(600)
            .ifBlank { userText.trim().take(260) }
        val actions = sanitizeActions(parsed.optJSONArray("actions"), userText)
        var reply = parsed.optString("reply").trim().take(1600)
            .ifBlank { "فهمت المحتوى وحفظته بشكل قابل للبحث." }

        if (actions.length() > 0 && falselyClaimsExecution(reply)) {
            reply = "فهمت طلبك وحددت الإجراء المناسب. سينفذه التطبيق فقط إذا كان آمناً وواضحاً."
        }

        val confidenceRaw = parsed.optDouble("confidence", 0.7)
        val confidence = if (confidenceRaw.isFinite()) confidenceRaw.coerceIn(0.0, 1.0) else 0.7

        return JSONObject()
            .put("ok", true)
            .put("engine", "local-litertlm")
            .put("model", "Qwen3-0.6B")
            .put("reply", reply)
            .put("classification", classification)
            .put("labels", cleanArray(parsed.optJSONArray("labels"), 10, 70))
            .put("keywords", cleanArray(parsed.optJSONArray("keywords"), 14, 90))
            .put("summary", summary)
            .put("confidence", confidence)
            .put("actions", actions)
    }

    private fun sanitizeActions(input: JSONArray?, userText: String): JSONArray {
        val out = JSONArray()
        if (input == null) return out
        for (i in 0 until minOf(input.length(), 5)) {
            val item = input.optJSONObject(i) ?: continue
            val type = item.optString("type").trim()
            if (type !in allowedActions) continue

            if (type == "enrich_previous_document" && !looksLikeDocumentDescription(userText)) {
                continue
            }

            val args = item.optJSONObject("args") ?: JSONObject()
            if (type == "search" && args.optString("query").isBlank()) {
                args.put("query", cleanSearchQuery(userText))
            }
            if (type == "search" && args.optString("query").isBlank()) continue

            val explicit = when (type) {
                "search" -> looksLikeSearch(userText)
                "enrich_previous_document" -> looksLikeDocumentDescription(userText)
                "archive_space" -> Regex("(?:أرشف|ارشف|أرشفة|ارشفة)", RegexOption.IGNORE_CASE).containsMatchIn(userText)
                "pin_space" -> Regex("(?:ثبّت|ثبت|تثبيت).*(?:مساحة|المساحة)", RegexOption.IGNORE_CASE).containsMatchIn(userText)
                "rename_space", "rename_last_document" ->
                    Regex("(?:سمّي|سمي|غيّر اسم|غير اسم|إعادة تسمية|اعادة تسمية)", RegexOption.IGNORE_CASE).containsMatchIn(userText)
                "move_last_item", "move_last_document" ->
                    Regex("(?:انقل|نقل|حرك|حرّك)", RegexOption.IGNORE_CASE).containsMatchIn(userText)
                "create_space" ->
                    Regex("(?:أنشئ|انشئ|اعمل|أعمل|افتح).*(?:مساحة|محادثة)", RegexOption.IGNORE_CASE).containsMatchIn(userText)
                else -> false
            }

            out.put(
                JSONObject()
                    .put("type", type)
                    .put("args", args)
                    .put("requires_confirmation", !explicit)
            )
        }
        return out
    }

    private fun extractJson(raw: String): JSONObject? {
        val withoutThinking = raw
            .replace(Regex("(?is)<think>.*?</think>"), "")
            .replace(Regex("(?is)<analysis>.*?</analysis>"), "")
            .trim()
        val fence = "\u0060\u0060\u0060"
        val clean = withoutThinking
            .removePrefix(fence + "json").removePrefix(fence + "JSON").removePrefix(fence)
            .removeSuffix(fence).trim()

        runCatching { return JSONObject(clean) }
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }

    private fun cleanArray(input: JSONArray?, max: Int, itemLimit: Int): JSONArray {
        val output = JSONArray()
        if (input == null) return output
        val seen = LinkedHashSet<String>()
        for (i in 0 until input.length()) {
            val value = input.optString(i).trim().take(itemLimit)
            if (value.isNotBlank() && seen.add(value)) {
                output.put(value)
                if (output.length() >= max) break
            }
        }
        return output
    }

    private fun falselyClaimsExecution(reply: String): Boolean {
        return Regex(
            "(?:تم\\\\s+(?:إنشاء|انشاء|إضافة|اضافة|أرشفة|ارشفة|نقل|تغيير|تثبيت)|أنشأت|انشأت|أضفت|اضفت|نقلت|أرشفت|ارشفت|ثبتت|غيّرت)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(reply)
    }

    private fun looksLikeSearch(text: String): Boolean =
        Regex("^(?:وين|أين|اين|ابحث|دور|فتش|find|search|suche|wo ist)", RegexOption.IGNORE_CASE)
            .containsMatchIn(text.trim())

    private fun looksLikeDocumentDescription(text: String): Boolean =
        Regex(
            "^(?:هاي|هذه|هي|هاد|هذا)\\\\s+(?:ورقة|الورقة|مستند|المستند|عقد|العقد|وثيقة|الوثيقة)(?:\\\\s|$)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text.trim())

    private fun cleanSearchQuery(text: String): String =
        text.trim()
            .replace(
                Regex("^(?:وين|أين|اين|ابحث(?:لي)?(?: عن)?|دور(?:لي)?(?: على)?|فتش(?:لي)?(?: عن)?|find|search for|suche nach|wo ist)(?:\\\\s+|$)", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(Regex("^(?:حطيت|حطيتلي|حفظت|خزنت|وضعت|حاطط)(?:\\\\s+|$)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[؟?]+$"), "")
            .trim()
}
