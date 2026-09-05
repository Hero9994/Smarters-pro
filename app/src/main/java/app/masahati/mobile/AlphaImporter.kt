package app.masahati.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class AlphaImportSummary(
    val spaces: Int,
    val messages: Int,
    val files: Int,
    val reminders: Int,
    val actions: Int
)

object AlphaImporter {
    private const val MAX_UNCOMPRESSED_BYTES = 1_500_000_000L

    fun importZip(context: Context, db: MasahatiDatabase, input: InputStream): AlphaImportSummary {
        val tempRoot = File(context.cacheDir, "masahati-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var totalBytes = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val safe = sanitizeEntry(entry.name) ?: error("نسخة احتياطية غير صالحة")
                    if (!entry.isDirectory) {
                        val out = File(tempRoot, safe)
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                if (totalBytes > MAX_UNCOMPRESSED_BYTES) error("النسخة الاحتياطية أكبر من الحد المسموح")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            val manifestFile = File(tempRoot, "data/masahati.json")
            if (!manifestFile.isFile) error("ملف masahati.json غير موجود")
            val root = JSONObject(manifestFile.readText(Charsets.UTF_8))
            if (root.optString("format") != "masahati-alpha-backup-v1") {
                error("صيغة النسخة الاحتياطية غير مدعومة")
            }

            val oldToNewSpace = mutableMapOf<Long, Long>()
            val oldToNewMessage = mutableMapOf<Long, Long>()
            val existingTitles = (db.listSpaces(false) + db.listSpaces(true)).map { it.title }.toMutableSet()
            val importSuffix = java.text.SimpleDateFormat("dd.MM HHmm", java.util.Locale.GERMANY).format(java.util.Date())

            val spaces = root.optJSONArray("spaces")
            var spaceCount = 0
            if (spaces != null) {
                for (i in 0 until spaces.length()) {
                    val item = spaces.optJSONObject(i) ?: continue
                    val oldId = item.optLong("id", -1L)
                    if (oldId <= 0L) continue
                    val originalTitle = item.optString("title").trim().ifBlank { "مساحة مستوردة" }
                    var title = originalTitle
                    var counter = 1
                    while (title in existingTitles) {
                        title = if (counter == 1) "$originalTitle — مستورد $importSuffix"
                        else "$originalTitle — مستورد $importSuffix ($counter)"
                        counter++
                    }
                    existingTitles += title
                    val createdAt = item.optLong("created_at", item.optLong("updated_at", System.currentTimeMillis()))
                    val updatedAt = item.optLong("updated_at", createdAt)
                    val newId = db.importSpace(
                        title = title,
                        pinned = item.optBoolean("pinned", false),
                        archived = item.optBoolean("archived", false),
                        createdAt = createdAt,
                        updatedAt = updatedAt
                    )
                    if (newId > 0L) {
                        oldToNewSpace[oldId] = newId
                        spaceCount++
                    }
                }
            }

            val messages = root.optJSONArray("messages")
            var messageCount = 0
            var fileCount = 0
            if (messages != null) {
                for (i in 0 until messages.length()) {
                    val item = messages.optJSONObject(i) ?: continue
                    val oldId = item.optLong("id", -1L)
                    val newSpaceId = oldToNewSpace[item.optLong("space_id", -1L)] ?: continue
                    val kind = item.optString("kind", "text")
                    val displayName = item.optNullableString("display_name")
                    val importedFile = if (kind == "file" && oldId > 0L) {
                        findFileEntry(tempRoot, oldId)?.let { source ->
                            val documents = File(context.filesDir, "documents").apply { mkdirs() }
                            val safeName = safeFileName(displayName ?: source.name.substringAfter('-'))
                            val target = File(documents, "import-${UUID.randomUUID()}-$safeName")
                            source.copyTo(target, overwrite = false)
                            fileCount++
                            target.absolutePath
                        }
                    } else null

                    val newId = db.importMessage(
                        spaceId = newSpaceId,
                        role = item.optString("role", "user"),
                        kind = kind,
                        text = item.optString("text", ""),
                        filePath = importedFile,
                        mimeType = item.optNullableString("mime_type"),
                        displayName = displayName,
                        ocrText = item.optNullableString("ocr_text"),
                        classification = item.optNullableString("classification"),
                        tags = item.optNullableString("tags"),
                        summary = item.optNullableString("summary"),
                        starred = item.optBoolean("starred", false),
                        createdAt = item.optLong("created_at", System.currentTimeMillis())
                    )
                    if (newId <= 0L) continue
                    oldToNewMessage[oldId] = newId
                    messageCount++

                    item.optJSONObject("document_meta")?.let { meta ->
                        db.upsertDocumentMeta(
                            DocumentMetaRow(
                                messageId = newId,
                                smartTitle = meta.optNullableString("smart_title"),
                                docType = meta.optNullableString("doc_type"),
                                organization = meta.optNullableString("organization"),
                                personNames = meta.optNullableString("person_names"),
                                referenceNumber = meta.optNullableString("reference_number"),
                                amountText = meta.optNullableString("amount_text"),
                                currency = meta.optNullableString("currency"),
                                issueDate = meta.optNullableString("issue_date"),
                                dueDate = meta.optNullableString("due_date"),
                                expiryDate = meta.optNullableString("expiry_date"),
                                actionRequired = meta.optBoolean("action_required", false),
                                actionText = meta.optNullableString("action_text"),
                                confidence = meta.optDouble("confidence").takeIf { !it.isNaN() },
                                evidenceJson = meta.optNullableString("evidence_json"),
                                extractedJson = null,
                                updatedAt = meta.optLong("updated_at", System.currentTimeMillis())
                            )
                        )
                    }

                    val importedRow = db.getMessage(newId)
                    if (importedRow?.kind == "file") {
                        importedFile?.let { path ->
                            runCatching {
                                AlphaDocumentProcessor.indexNewFile(db, newId, File(path), importedRow.ocrText)
                            }
                        }
                    }
                    if (item.optBoolean("trashed", false)) db.deleteMessage(newId)
                }
            }

            val actions = root.optJSONArray("open_actions")
            var actionCount = 0
            if (actions != null) {
                for (i in 0 until actions.length()) {
                    val item = actions.optJSONObject(i) ?: continue
                    val newSpaceId = oldToNewSpace[item.optLong("space_id", -1L)] ?: continue
                    val oldMessageId = item.optLong("message_id", -1L)
                    val newMessageId = oldToNewMessage[oldMessageId]
                    val createdAt = item.optLong("created_at", System.currentTimeMillis())
                    val id = db.importActionItem(
                        spaceId = newSpaceId,
                        messageId = newMessageId,
                        kind = item.optString("kind", "imported_action"),
                        title = item.optString("title", "إجراء مستورد"),
                        details = item.optNullableString("details"),
                        dueAt = item.optLongOrNull("due_at"),
                        status = item.optString("status", "open"),
                        sourceExcerpt = item.optNullableString("source_excerpt"),
                        createdAt = createdAt,
                        updatedAt = item.optLong("updated_at", createdAt)
                    )
                    if (id > 0L) actionCount++
                }
            }

            val reminders = root.optJSONArray("active_reminders")
            var reminderCount = 0
            if (reminders != null) {
                for (i in 0 until reminders.length()) {
                    val item = reminders.optJSONObject(i) ?: continue
                    val newSpaceId = oldToNewSpace[item.optLong("space_id", -1L)] ?: continue
                    val id = db.importReminder(
                        spaceId = newSpaceId,
                        title = item.optString("title", "تذكير مستورد"),
                        body = item.optString("body", ""),
                        repeatRule = item.optString("repeat_rule", "none"),
                        dayOfWeek = item.optIntOrNull("day_of_week"),
                        hour = item.optIntOrNull("hour"),
                        minute = item.optIntOrNull("minute"),
                        nextFireAt = item.optLongOrNull("next_fire_at"),
                        enabled = item.optBoolean("enabled", true),
                        createdAt = item.optLong("created_at", System.currentTimeMillis())
                    )
                    if (id > 0L) reminderCount++
                }
            }

            return AlphaImportSummary(spaceCount, messageCount, fileCount, reminderCount, actionCount)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun findFileEntry(root: File, oldMessageId: Long): File? {
        val files = File(root, "files")
        if (!files.isDirectory) return null
        return files.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("$oldMessageId-") }
    }

    private fun sanitizeEntry(name: String): String? {
        val normalized = name.replace('\\', '/').trimStart('/')
        if (normalized.isBlank() || normalized.contains("../") || normalized == "..") return null
        if (!(normalized == "README.txt" || normalized == "data/masahati.json" || normalized.startsWith("files/"))) return null
        return normalized
    }

    private fun safeFileName(value: String): String =
        value.replace(Regex("[\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim().take(140).ifBlank { "ملف" }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optLongOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return optLong(name).takeIf { it != 0L }
    }

    private fun JSONObject.optIntOrNull(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return optInt(name)
    }
}
