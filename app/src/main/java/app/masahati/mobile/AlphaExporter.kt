package app.masahati.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AlphaExporter {
    fun export(context: Context, db: MasahatiDatabase, output: OutputStream) {
        val spaces = (db.listSpaces(false) + db.listSpaces(true)).distinctBy { it.id }
        val allMessages = db.allMessagesIncludingTrash()
        val trashIds = db.listTrash(500).mapTo(mutableSetOf()) { it.id }
        val reminders = db.listActiveReminders()
        val actions = db.listOpenActionItems(500)

        val root = JSONObject()
            .put("format", "masahati-alpha-backup-v1")
            .put("exported_at", ZonedDateTime.now().toString())
            .put("app", "مساحاتي alpha")

        root.put("spaces", JSONArray().apply {
            spaces.forEach { s ->
                put(JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("pinned", s.pinned)
                    .put("archived", s.archived)
                    .put("updated_at", s.updatedAt))
            }
        })

        root.put("messages", JSONArray().apply {
            allMessages.forEach { m ->
                val meta = if (m.kind == "file") db.getDocumentMeta(m.id) else null
                put(JSONObject()
                    .put("id", m.id)
                    .put("space_id", m.spaceId)
                    .put("role", m.role)
                    .put("kind", m.kind)
                    .put("text", m.text)
                    .put("display_name", m.displayName)
                    .put("mime_type", m.mimeType)
                    .put("ocr_text", m.ocrText)
                    .put("classification", m.classification)
                    .put("tags", m.tags)
                    .put("summary", m.summary)
                    .put("starred", m.starred)
                    .put("trashed", m.id in trashIds)
                    .put("created_at", m.createdAt)
                    .put("document_meta", meta?.let(::metaJson)))
            }
        })

        root.put("active_reminders", JSONArray().apply {
            reminders.forEach { r ->
                put(JSONObject()
                    .put("id", r.id)
                    .put("space_id", r.spaceId)
                    .put("title", r.title)
                    .put("body", r.body)
                    .put("repeat_rule", r.repeatRule)
                    .put("day_of_week", r.dayOfWeek)
                    .put("hour", r.hour)
                    .put("minute", r.minute)
                    .put("next_fire_at", r.nextFireAt)
                    .put("enabled", r.enabled)
                    .put("created_at", r.createdAt))
            }
        })

        root.put("open_actions", JSONArray().apply {
            actions.forEach { a ->
                put(JSONObject()
                    .put("id", a.id)
                    .put("space_id", a.spaceId)
                    .put("message_id", a.messageId)
                    .put("kind", a.kind)
                    .put("title", a.title)
                    .put("details", a.details)
                    .put("due_at", a.dueAt)
                    .put("status", a.status)
                    .put("source_excerpt", a.sourceExcerpt)
                    .put("created_at", a.createdAt))
            }
        })

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            putBytes(zip, "data/masahati.json", root.toString(2).toByteArray(Charsets.UTF_8))
            allMessages.filter { it.kind == "file" && !it.filePath.isNullOrBlank() }.forEach { m ->
                val file = File(m.filePath!!)
                if (!file.isFile) return@forEach
                val safeName = (m.displayName ?: file.name)
                    .replace(Regex("[\\/:*?\"<>|]"), "_")
                    .take(140)
                zip.putNextEntry(ZipEntry("files/${m.id}-$safeName"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val readme = """
مساحاتي alpha backup
تم إنشاء هذه النسخة من بيانات المستخدم وملفاته.
الملف data/masahati.json بصيغة JSON مفتوحة، والملفات الأصلية موجودة داخل files/.
""".trimIndent()
            putBytes(zip, "README.txt", readme.toByteArray(Charsets.UTF_8))
        }
    }

    private fun metaJson(m: DocumentMetaRow): JSONObject = JSONObject()
        .put("smart_title", m.smartTitle)
        .put("doc_type", m.docType)
        .put("organization", m.organization)
        .put("person_names", m.personNames)
        .put("reference_number", m.referenceNumber)
        .put("amount_text", m.amountText)
        .put("currency", m.currency)
        .put("issue_date", m.issueDate)
        .put("due_date", m.dueDate)
        .put("expiry_date", m.expiryDate)
        .put("action_required", m.actionRequired)
        .put("action_text", m.actionText)
        .put("confidence", m.confidence)
        .put("evidence_json", m.evidenceJson)
        .put("updated_at", m.updatedAt)

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
}
