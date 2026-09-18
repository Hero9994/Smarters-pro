package app.masahati.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AgentExecution(val note: String, val reminderCreated: Boolean = false, val exactPermissionNeeded: Boolean = false)

class AgentActionExecutor(private val context: Context, private val db: MasahatiDatabase) {
    fun execute(source: MessageRow, actions: JSONArray?, focusedDocumentId: Long?, previousItemId: Long?,
                lastDocumentId: Long?, reminderResolved: Boolean = false): AgentExecution {
        if (actions == null || db.getMessage(source.id)?.spaceId != source.spaceId) return AgentExecution("")
        val notes = mutableListOf<String>()
        var reminderCreated = false
        var needsExact = false
        val seen = mutableSetOf<String>()
        fun document(id: Long?) = id?.let(db::getMessage)?.takeIf { it.spaceId == source.spaceId && it.kind == "file" }
        for (i in 0 until minOf(actions.length(), 4)) {
            val action = actions.optJSONObject(i) ?: continue
            val type = action.optString("type")
            if (!seen.add(type) || !AgentActionPolicy.allows(type, source.text, reminderResolved)) continue
            if (action.optBoolean("requires_confirmation", true)) {
                notes += "هذا التغيير يحتاج تأكيداً واضحاً منك؛ لم يُنفّذ."
                continue
            }
            val args = action.optJSONObject("args") ?: JSONObject()
            when (type) {
                "create_reminder" -> {
                    val created = ReminderScheduler.createFromAgent(context, db, source.spaceId, args, source.text)
                    if (created == null) notes += "الموعد غير واضح بما يكفي لإنشاء التنبيه."
                    else {
                        reminderCreated = true
                        needsExact = needsExact || !created.exact
                        notes += "تم إنشاء تنبيه فعلي: ${created.description}." +
                            if (created.exact) "" else " قد يتأخر بضع دقائق حتى تفعّل دقة التنبيهات."
                    }
                }
                "enrich_previous_document" -> {
                    val target = document(focusedDocumentId)
                    if (target == null) { notes += "المستند المقصود لم يعد موجوداً في هذه المساحة."; continue }
                    val summary = args.optString("summary").trim().take(2000).ifBlank { target.summary.orEmpty() }
                    fun values(key: String): List<String> {
                        val array = args.optJSONArray(key) ?: return emptyList()
                        return (0 until array.length()).map { array.optString(it).trim().take(90) }
                    }
                    val tags = (values("labels") + values("keywords") + target.tags.orEmpty().split('،'))
                        .map(String::trim).filter(String::isNotBlank).distinct().take(12).joinToString("، ")
                    db.updateAi(target.id, "document", tags, summary,
                        JSONObject().put("source", "user_clarification").put("summary", summary).put("tags", tags).toString())
                    notes += "ربطت المعلومة بالمستند المقصود وحدّثت وصفه وكلمات البحث."
                }
                "search" -> {
                    val query = args.optString("query").trim().take(180)
                    if (query.isBlank()) continue
                    val found = db.search(query, 8)
                    notes += if (found.isEmpty()) "لم أجد نتيجة محلية مطابقة لـ «$query»." else "وجدت محلياً:\n" + found.mapNotNull { row ->
                        val title = db.getSpace(row.spaceId)?.title ?: return@mapNotNull null
                        "• $title — ${row.displayName ?: row.summary?.takeIf(String::isNotBlank) ?: row.text.take(85)}"
                    }.joinToString("\n")
                }
                "archive_space", "pin_space" -> {
                    val name = args.optString("space_name").trim()
                    val target = if (name.isBlank()) db.getSpace(source.spaceId) else db.findSpaceByTitle(name)
                    if (target == null) { notes += "لم أجد المساحة المطلوبة؛ لم أغيّر مساحة أخرى."; continue }
                    if (type == "archive_space") { db.setArchived(target.id, true); notes += "تمت أرشفة مساحة «${target.title}»." }
                    else { db.setPinned(target.id, true); notes += "تم تثبيت مساحة «${target.title}»." }
                }
                "create_space" -> {
                    val name = args.optString("name").trim().take(120)
                    if (name.isBlank()) continue
                    if (db.findSpaceByTitle(name) != null) notes += "المساحة «$name» موجودة بالفعل."
                    else { db.createSpace(name); notes += "تم إنشاء مساحة «$name»." }
                }
                "rename_space" -> {
                    val name = args.optString("new_name").ifBlank { args.optString("title") }.trim().take(120)
                    if (name.isBlank()) continue
                    db.renameSpace(source.spaceId, name)
                    notes += "تم تغيير اسم المساحة إلى «$name»."
                }
                "rename_last_document" -> {
                    val name = args.optString("new_name").trim().take(180)
                    val target = document(lastDocumentId)
                    if (target == null) { notes += "المستند المقصود لم يعد موجوداً في هذه المساحة."; continue }
                    if (name.isBlank()) continue
                    db.renameMessageDisplayName(target.id, name)
                    notes += "تم تغيير اسم المستند إلى «$name»."
                }
                "move_last_item", "move_last_document" -> {
                    val name = args.optString("target_space").ifBlank { args.optString("space_name") }.trim()
                    val targetSpace = db.findSpaceByTitle(name)
                    val target = if (type == "move_last_document") document(lastDocumentId) else
                        previousItemId?.let(db::getMessage)?.takeIf { it.spaceId == source.spaceId && it.id != source.id }
                    if (targetSpace == null || target == null) { notes += "لم أجد العنصر أو المساحة المطلوبة للنقل."; continue }
                    db.moveMessage(target.id, targetSpace.id)
                    notes += "تم نقل العنصر المقصود إلى «${targetSpace.title}»."
                }
            }
        }
        return AgentExecution(notes.joinToString("\n"), reminderCreated, needsExact)
    }
}
