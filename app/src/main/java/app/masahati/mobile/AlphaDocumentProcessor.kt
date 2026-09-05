package app.masahati.mobile

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

data class AlphaIndexResult(
    val duplicate: MessageRow?,
    val hash: String
)

object AlphaDocumentProcessor {
    fun indexNewFile(db: MasahatiDatabase, messageId: Long, file: File, ocrText: String?): AlphaIndexResult {
        val hash = sha256(file)
        val duplicate = db.findDuplicateByHash(hash, messageId)
        db.setMessageContentHash(messageId, hash)
        val chunks = chunkText(ocrText.orEmpty())
        if (chunks.isNotEmpty()) db.replaceDocumentChunks(messageId, chunks)
        return AlphaIndexResult(duplicate, hash)
    }

    fun applyAgentResult(db: MasahatiDatabase, messageId: Long, result: JSONObject) {
        val row = db.getMessage(messageId) ?: return
        if (row.kind != "file") return

        val doc = result.optJSONObject("document")
        val smartTitle = doc?.optString("smart_title")?.trim().orEmpty().ifBlank { null }
        val docType = doc?.optString("doc_type")?.trim().orEmpty().ifBlank { result.optString("classification").takeIf { it == "document" } }
        val organization = doc?.optString("organization")?.trim().orEmpty().ifBlank { null }
        val people = doc?.optJSONArray("person_names")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString("، ").ifBlank { null }
        }
        val reference = doc?.optString("reference_number")?.trim().orEmpty().ifBlank { null }
        val amount = doc?.optString("amount_text")?.trim().orEmpty().ifBlank { null }
        val currency = doc?.optString("currency")?.trim().orEmpty().ifBlank { null }
        val issueDate = doc?.optString("issue_date")?.trim().orEmpty().ifBlank { null }
        val dueDate = doc?.optString("due_date")?.trim().orEmpty().ifBlank { null }
        val expiryDate = doc?.optString("expiry_date")?.trim().orEmpty().ifBlank { null }
        val actionRequired = doc?.optBoolean("action_required", false) == true
        val actionText = doc?.optString("action_text")?.trim().orEmpty().ifBlank { null }
        val confidence = doc?.optDouble("confidence")?.takeIf { !it.isNaN() }
            ?: result.optDouble("confidence").takeIf { !it.isNaN() }
        val evidenceJson = doc?.optJSONArray("evidence")?.toString()

        val meta = DocumentMetaRow(
            messageId = messageId,
            smartTitle = smartTitle,
            docType = docType,
            organization = organization,
            personNames = people,
            referenceNumber = reference,
            amountText = amount,
            currency = currency,
            issueDate = issueDate,
            dueDate = dueDate,
            expiryDate = expiryDate,
            actionRequired = actionRequired,
            actionText = actionText,
            confidence = confidence,
            evidenceJson = evidenceJson,
            extractedJson = doc?.toString(),
            updatedAt = System.currentTimeMillis()
        )
        db.upsertDocumentMeta(meta)

        if (!smartTitle.isNullOrBlank() && row.displayName.orEmpty().startsWith("Scan-", ignoreCase = true)) {
            db.renameMessageDisplayName(messageId, smartDisplayName(smartTitle, row.displayName))
        }

        db.clearGeneratedActionItemsForMessage(messageId)
        if (actionRequired && !actionText.isNullOrBlank()) {
            val dueAt = parseDateAtMorning(dueDate)
            val excerpt = doc?.optJSONArray("evidence")?.let { evidence ->
                for (i in 0 until evidence.length()) {
                    val e = evidence.optJSONObject(i) ?: continue
                    if (e.optString("field") in setOf("action_text", "due_date", "expiry_date")) {
                        val x = e.optString("excerpt").trim()
                        if (x.isNotBlank()) return@let x
                    }
                }
                null
            }
            db.createActionItem(
                spaceId = row.spaceId,
                messageId = row.id,
                kind = if (dueAt != null) "deadline" else "document_action",
                title = actionText.take(180),
                details = result.optString("summary").takeIf { it.isNotBlank() },
                dueAt = dueAt,
                sourceExcerpt = excerpt
            )
        }

        val searchable = listOfNotNull(
            smartTitle,
            docType,
            organization,
            people,
            reference,
            result.optString("summary").takeIf { it.isNotBlank() },
            row.ocrText
        ).joinToString("\n")
        val chunks = chunkText(searchable)
        if (chunks.isNotEmpty()) db.replaceDocumentChunks(messageId, chunks)
    }

    fun chunkText(text: String, maxChars: Int = 1400, overlap: Int = 180): List<String> {
        val clean = text.replace("\r\n", "\n").trim()
        if (clean.isBlank()) return emptyList()
        if (clean.length <= maxChars) return listOf(clean)

        val result = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            var end = (start + maxChars).coerceAtMost(clean.length)
            if (end < clean.length) {
                val paragraph = clean.lastIndexOf("\n\n", end).takeIf { it > start + maxChars / 2 }
                val line = clean.lastIndexOf("\n", end).takeIf { it > start + maxChars / 2 }
                val space = clean.lastIndexOf(' ', end).takeIf { it > start + maxChars / 2 }
                end = paragraph ?: line ?: space ?: end
            }
            val chunk = clean.substring(start, end).trim()
            if (chunk.isNotBlank()) result += chunk
            if (end >= clean.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return result
    }

    private fun smartDisplayName(title: String, oldName: String?): String {
        val clean = title
            .replace(Regex("[\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
            .ifBlank { "مستند" }
        val ext = oldName?.substringAfterLast('.', "")?.takeIf { it.length in 2..6 }
        return if (ext == null) clean else "$clean.$ext"
    }

    private fun parseDateAtMorning(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            LocalDate.parse(value).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
