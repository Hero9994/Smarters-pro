package app.masahati.mobile

import org.json.JSONArray
import org.json.JSONObject

/** Device commands are parsed before any model request, including in local-only spaces. */
object LocalCommandParser {
    fun analyze(text: String, spaceTitle: String, hasFocusedDocument: Boolean = false): JSONObject? {
        val q = text.trim().replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        fun capture(pattern: String): String? = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(q)
            ?.groupValues?.get(1)?.trim()?.trim('«', '»', '"', '\'')?.trim()?.takeIf(String::isNotBlank)
        val create = capture("(?:اعمل|أعمل|انشئ|أنشئ|سوي|create)\\s*(?:لي)?\\s*(?:مساحة|space)\\s+(?:(?:باسم|اسمها|اسم|اسمها هو|named)\\s+)?(.+)")
        val renameFile = capture("(?:غير\\s+اسم|سمي|rename)\\s+(?:آخر|اخر)\\s+(?:ورقة|مستند|ملف)\\s+(?:(?:إلى|الى|باسم)\\s+)?(.+)")
        val renameSpace = capture("(?:غير\\s+اسم|سمي|rename)\\s+(?:هالمساحة|هذه\\s+المساحة|المساحة)\\s+(?:(?:إلى|الى|باسم)\\s+)?(.+)")
        val moveFile = capture("(?:انقل|نقل|حرك|move)\\s+(?:آخر|اخر)\\s+(?:ورقة|مستند|ملف)\\s+(?:(?:إلى|الى)\\s+)?(?:مساحة|space)\\s+(.+)")
        val moveItem = capture("(?:انقل|نقل|حرك|move)\\s+(?:آخر|اخر)\\s+(?:شي|شيء|عنصر|ملاحظة)\\s+(?:(?:إلى|الى)\\s+)?(?:مساحة|space)\\s+(.+)")
        val archive = Regex("^(?:أرشف|ارشف|archive)\\s+(?:هالمساحة|هذه المساحة|المساحة)$", RegexOption.IGNORE_CASE).matches(q)
        val pin = Regex("^(?:ثبت|pin)\\s+(?:هالمساحة|هذه المساحة|المساحة)$", RegexOption.IGNORE_CASE).matches(q)
        val enrich = hasFocusedDocument && AgentActionPolicy.allows("enrich_previous_document", text)
        val search = Regex("^(?:ابحث(?:لي)?(?: عن)?|دور(?:لي)?(?: على)?|فتش(?:لي)?(?: عن)?|find|search for|suche nach)\\s+(.+)", RegexOption.IGNORE_CASE)
            .matchEntire(q)?.groupValues?.get(1)?.trim()?.trimEnd('؟', '?')
        val type: String
        val args = JSONObject()
        when {
            enrich -> {
                type = "enrich_previous_document"
                args.put("summary", text.take(1500)).put("labels", JSONArray(listOf("مستند"))).put("keywords", JSONArray())
            }
            !search.isNullOrBlank() -> { type = "search"; args.put("query", search.take(180)) }
            create != null -> { type = "create_space"; args.put("name", create.take(120)) }
            renameFile != null -> { type = "rename_last_document"; args.put("new_name", renameFile.take(180)) }
            renameSpace != null -> { type = "rename_space"; args.put("new_name", renameSpace.take(120)) }
            moveFile != null -> { type = "move_last_document"; args.put("target_space", moveFile.take(120)) }
            moveItem != null -> { type = "move_last_item"; args.put("target_space", moveItem.take(120)) }
            archive -> { type = "archive_space"; args.put("space_name", spaceTitle) }
            pin -> { type = "pin_space"; args.put("space_name", spaceTitle) }
            else -> return null
        }
        // A combined/ambiguous request needs interpretation, not an invented name or destination.
        if ((type != "search" && (q.contains('؟') || q.contains('?'))) || Regex("\\s(?:ولا|وبعدين|ثم|وبعدها)\\s").containsMatchIn(q)) return null
        if (!AgentActionPolicy.allows(type, text)) return null
        return JSONObject().put("ok", true).put("engine", "local-command-parser")
            .put("classification", if (enrich) "document" else if (type == "search") "search" else "command").put("labels", JSONArray()).put("keywords", JSONArray())
            .put("summary", text.take(320)).put("confidence", 1.0).put("reply", "فهمت الأمر.")
            .put("actions", JSONArray().put(JSONObject().put("type", type).put("args", args).put("requires_confirmation", false)))
    }
}
