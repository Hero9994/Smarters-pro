package app.masahati.mobile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

data class SpaceRow(
    val id: Long,
    val title: String,
    val pinned: Boolean,
    val archived: Boolean,
    val updatedAt: Long
)

data class MessageRow(
    val id: Long,
    val spaceId: Long,
    val role: String,
    val kind: String,
    val text: String,
    val filePath: String?,
    val mimeType: String?,
    val displayName: String?,
    val ocrText: String?,
    val classification: String?,
    val tags: String?,
    val summary: String?,
    val starred: Boolean,
    val createdAt: Long
)

data class DocumentMetaRow(
    val messageId: Long,
    val smartTitle: String?,
    val docType: String?,
    val organization: String?,
    val personNames: String?,
    val referenceNumber: String?,
    val amountText: String?,
    val currency: String?,
    val issueDate: String?,
    val dueDate: String?,
    val expiryDate: String?,
    val actionRequired: Boolean,
    val actionText: String?,
    val confidence: Double?,
    val evidenceJson: String?,
    val extractedJson: String?,
    val updatedAt: Long
)

data class ActionItemRow(
    val id: Long,
    val spaceId: Long,
    val messageId: Long?,
    val kind: String,
    val title: String,
    val details: String?,
    val dueAt: Long?,
    val status: String,
    val sourceExcerpt: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class TrackedDocumentRow(
    val messageId: Long,
    val spaceId: Long,
    val displayName: String?,
    val smartTitle: String?,
    val dueDate: String?,
    val expiryDate: String?,
    val actionText: String?
)

data class DocumentChunkRow(
    val id: Long,
    val messageId: Long,
    val chunkIndex: Int,
    val text: String,
    val embedding: ByteArray?
)

data class MessageVersionRow(
    val id: Long,
    val messageId: Long,
    val reason: String,
    val text: String?,
    val displayName: String?,
    val ocrText: String?,
    val classification: String?,
    val tags: String?,
    val summary: String?,
    val createdAt: Long
)

data class ReminderRow(
    val id: Long,
    val spaceId: Long,
    val title: String,
    val body: String,
    val repeatRule: String,
    val dayOfWeek: Int?,
    val hour: Int?,
    val minute: Int?,
    val nextFireAt: Long?,
    val enabled: Boolean,
    val deliveredAt: Long?,
    val conditionActionId: Long? = null,
    val createdAt: Long
)

class MasahatiDatabase(context: Context) : SQLiteOpenHelper(context, "masahati_v05.db", null, 8) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE spaces(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              pinned INTEGER NOT NULL DEFAULT 0,
              archived INTEGER NOT NULL DEFAULT 0,
              focus_message_id INTEGER,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              space_id INTEGER NOT NULL,
              role TEXT NOT NULL,
              kind TEXT NOT NULL,
              text TEXT NOT NULL DEFAULT '',
              file_path TEXT,
              mime_type TEXT,
              display_name TEXT,
              ocr_text TEXT,
              classification TEXT,
              tags TEXT,
              summary TEXT,
              starred INTEGER NOT NULL DEFAULT 0,
              ai_json TEXT,
              content_hash TEXT,
              deleted_at INTEGER,
              created_at INTEGER NOT NULL,
              FOREIGN KEY(space_id) REFERENCES spaces(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_space_created ON messages(space_id, created_at)")
        db.execSQL("CREATE INDEX idx_spaces_archived_pinned ON spaces(archived, pinned, updated_at)")
        createReminderTable(db)
        createAlphaTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DELETE FROM spaces WHERE title IN ('ملاحظات','يومي','أوراقي','أفكار المشروع') AND NOT EXISTS (SELECT 1 FROM messages WHERE messages.space_id = spaces.id)")
        }
        if (oldVersion < 3) createReminderTable(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE spaces ADD COLUMN focus_message_id INTEGER")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN delivered_at INTEGER")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE messages ADD COLUMN content_hash TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN deleted_at INTEGER")
            createAlphaTables(db)
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN condition_action_id INTEGER")
        }
    }

    private fun createReminderTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS reminders(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              space_id INTEGER NOT NULL,
              title TEXT NOT NULL,
              body TEXT NOT NULL,
              repeat_rule TEXT NOT NULL DEFAULT 'none',
              day_of_week INTEGER,
              hour INTEGER,
              minute INTEGER,
              next_fire_at INTEGER,
              enabled INTEGER NOT NULL DEFAULT 1,
              delivered_at INTEGER,
              condition_action_id INTEGER,
              created_at INTEGER NOT NULL,
              FOREIGN KEY(space_id) REFERENCES spaces(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reminders_active_next ON reminders(enabled, next_fire_at)")
    }

    private fun createAlphaTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS document_meta(
              message_id INTEGER PRIMARY KEY,
              smart_title TEXT,
              doc_type TEXT,
              organization TEXT,
              person_names TEXT,
              reference_number TEXT,
              amount_text TEXT,
              currency TEXT,
              issue_date TEXT,
              due_date TEXT,
              expiry_date TEXT,
              action_required INTEGER NOT NULL DEFAULT 0,
              action_text TEXT,
              confidence REAL,
              evidence_json TEXT,
              extracted_json TEXT,
              updated_at INTEGER NOT NULL,
              FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS action_items(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              space_id INTEGER NOT NULL,
              message_id INTEGER,
              kind TEXT NOT NULL,
              title TEXT NOT NULL,
              details TEXT,
              due_at INTEGER,
              status TEXT NOT NULL DEFAULT 'open',
              source_excerpt TEXT,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              FOREIGN KEY(space_id) REFERENCES spaces(id) ON DELETE CASCADE,
              FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS document_chunks(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              message_id INTEGER NOT NULL,
              chunk_index INTEGER NOT NULL,
              text TEXT NOT NULL,
              embedding BLOB,
              created_at INTEGER NOT NULL,
              UNIQUE(message_id, chunk_index),
              FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_versions(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              message_id INTEGER NOT NULL,
              reason TEXT NOT NULL,
              text TEXT,
              display_name TEXT,
              ocr_text TEXT,
              classification TEXT,
              tags TEXT,
              summary TEXT,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_hash ON messages(content_hash)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_deleted ON messages(deleted_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_action_items_due ON action_items(status, due_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_meta_expiry ON document_meta(expiry_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunks_message ON document_chunks(message_id, chunk_index)")
    }

    fun listSpaces(archived: Boolean = false, query: String = ""): List<SpaceRow> {
        val args = mutableListOf(if (archived) "1" else "0")
        val where = StringBuilder("archived = ?")
        if (query.isNotBlank()) {
            where.append(" AND title LIKE ?")
            args += "%${query.trim()}%"
        }
        val c = readableDatabase.query(
            "spaces", null, where.toString(), args.toTypedArray(), null, null,
            "pinned DESC, updated_at DESC"
        )
        return c.use { cursor -> buildList { while (cursor.moveToNext()) add(spaceFrom(cursor)) } }
    }

    fun createSpace(title: String): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert("spaces", null, ContentValues().apply {
            put("title", title.trim().ifBlank { "مساحة جديدة" })
            put("created_at", now)
            put("updated_at", now)
        })
        return id
    }

    fun findSpaceByTitle(title: String): SpaceRow? {
        val c = readableDatabase.query(
            "spaces", null, "LOWER(title)=LOWER(?)", arrayOf(title.trim()), null, null, null, "1"
        )
        return c.use { if (it.moveToFirst()) spaceFrom(it) else null }
    }

    fun getSpace(id: Long): SpaceRow? {
        val c = readableDatabase.query("spaces", null, "id=?", arrayOf(id.toString()), null, null, null, "1")
        return c.use { if (it.moveToFirst()) spaceFrom(it) else null }
    }

    fun renameSpace(id: Long, title: String) {
        writableDatabase.update("spaces", ContentValues().apply {
            put("title", title.trim().take(120))
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun setPinned(id: Long, pinned: Boolean) {
        writableDatabase.update("spaces", ContentValues().apply {
            put("pinned", if (pinned) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun setArchived(id: Long, archived: Boolean) {
        writableDatabase.update("spaces", ContentValues().apply {
            put("archived", if (archived) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun deleteSpace(id: Long) {
        writableDatabase.delete("messages", "space_id=?", arrayOf(id.toString()))
        writableDatabase.delete("spaces", "id=?", arrayOf(id.toString()))
    }

    fun insertText(spaceId: Long, role: String, text: String): Long = insertMessage(
        spaceId = spaceId,
        role = role,
        kind = "text",
        text = text
    )

    fun insertFile(
        spaceId: Long,
        role: String,
        displayName: String,
        filePath: String,
        mimeType: String,
        ocrText: String? = null,
        text: String = ""
    ): Long {
        val id = insertMessage(
            spaceId = spaceId,
            role = role,
            kind = "file",
            text = text,
            filePath = filePath,
            mimeType = mimeType,
            displayName = displayName,
            ocrText = ocrText
        )
        if (id > 0L) setFocusedMessage(spaceId, id)
        return id
    }

    private fun insertMessage(
        spaceId: Long,
        role: String,
        kind: String,
        text: String,
        filePath: String? = null,
        mimeType: String? = null,
        displayName: String? = null,
        ocrText: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert("messages", null, ContentValues().apply {
            put("space_id", spaceId)
            put("role", role)
            put("kind", kind)
            put("text", text)
            put("file_path", filePath)
            put("mime_type", mimeType)
            put("display_name", displayName)
            put("ocr_text", ocrText)
            put("created_at", now)
        })
        writableDatabase.update("spaces", ContentValues().apply { put("updated_at", now) }, "id=?", arrayOf(spaceId.toString()))
        return id
    }

    fun updateOcr(messageId: Long, ocrText: String) {
        saveMessageVersion(messageId, "ocr_update")
        writableDatabase.update("messages", ContentValues().apply { put("ocr_text", ocrText) }, "id=?", arrayOf(messageId.toString()))
    }

    fun updateAi(messageId: Long, classification: String?, tags: String?, summary: String?, aiJson: String) {
        saveMessageVersion(messageId, "ai_update")
        writableDatabase.update("messages", ContentValues().apply {
            put("classification", classification)
            put("tags", tags)
            put("summary", summary)
            put("ai_json", aiJson)
        }, "id=?", arrayOf(messageId.toString()))
    }

    fun listMessages(spaceId: Long): List<MessageRow> {
        val c = readableDatabase.query(
            "messages", null, "space_id=? AND deleted_at IS NULL", arrayOf(spaceId.toString()), null, null, "created_at ASC, id ASC"
        )
        return c.use { cursor -> buildList { while (cursor.moveToNext()) add(messageFrom(cursor)) } }
    }

    fun recentForAi(spaceId: Long, limit: Int = 20): List<MessageRow> {
        val c = readableDatabase.query(
            "messages",
            null,
            "space_id=? AND deleted_at IS NULL AND (text<>'' OR (ocr_text IS NOT NULL AND ocr_text<>'') OR (summary IS NOT NULL AND summary<>'') OR (display_name IS NOT NULL AND display_name<>''))",
            arrayOf(spaceId.toString()),
            null,
            null,
            "created_at DESC, id DESC",
            limit.coerceIn(1, 30).toString()
        )
        return c.use { cursor -> buildList { while (cursor.moveToNext()) add(messageFrom(cursor)) } }.reversed()
    }

    fun lastUserMessage(spaceId: Long): MessageRow? {
        val c = readableDatabase.query(
            "messages", null, "space_id=? AND role='user' AND deleted_at IS NULL", arrayOf(spaceId.toString()), null, null,
            "created_at DESC, id DESC", "1"
        )
        return c.use { if (it.moveToFirst()) messageFrom(it) else null }
    }

    fun lastFileMessage(spaceId: Long): MessageRow? {
        val c = readableDatabase.query(
            "messages", null, "space_id=? AND kind='file' AND deleted_at IS NULL", arrayOf(spaceId.toString()), null, null,
            "created_at DESC, id DESC", "1"
        )
        return c.use { if (it.moveToFirst()) messageFrom(it) else null }
    }

    fun getMessage(messageId: Long): MessageRow? {
        val c = readableDatabase.query(
            "messages", null, "id=? AND deleted_at IS NULL", arrayOf(messageId.toString()), null, null, null, "1"
        )
        return c.use { if (it.moveToFirst()) messageFrom(it) else null }
    }

    fun setFocusedMessage(spaceId: Long, messageId: Long?) {
        writableDatabase.update(
            "spaces",
            ContentValues().apply {
                if (messageId == null) putNull("focus_message_id") else put("focus_message_id", messageId)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(spaceId.toString())
        )
    }

    fun focusedMessage(spaceId: Long): MessageRow? {
        val c = readableDatabase.rawQuery(
            """
            SELECT m.*
            FROM spaces s
            JOIN messages m ON m.id = s.focus_message_id
            WHERE s.id=? AND m.space_id=s.id
            LIMIT 1
            """.trimIndent(),
            arrayOf(spaceId.toString())
        )
        return c.use { if (it.moveToFirst()) messageFrom(it) else null }
    }

    fun focusedDocument(spaceId: Long): MessageRow? {
        val focused = focusedMessage(spaceId)
        return if (focused?.kind == "file") focused else null
    }

    fun moveMessage(messageId: Long, targetSpaceId: Long) {
        val row = getMessage(messageId)
        if (row != null) {
            val oldSpaceId = row.spaceId
            writableDatabase.update("messages", ContentValues().apply { put("space_id", targetSpaceId) }, "id=?", arrayOf(messageId.toString()))
            writableDatabase.execSQL(
                "UPDATE spaces SET focus_message_id=NULL WHERE id=? AND focus_message_id=?",
                arrayOf(oldSpaceId, messageId)
            )
            writableDatabase.update("spaces", ContentValues().apply { put("updated_at", System.currentTimeMillis()) }, "id=?", arrayOf(targetSpaceId.toString()))
            if (row.kind == "file") setFocusedMessage(targetSpaceId, messageId)
        }
    }

    fun setMessageStarred(messageId: Long, starred: Boolean) {
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("starred", if (starred) 1 else 0) },
            "id=?",
            arrayOf(messageId.toString())
        )
    }

    fun listStarred(spaceId: Long): List<MessageRow> {
        val c = readableDatabase.query(
            "messages",
            null,
            "space_id=? AND starred=1",
            arrayOf(spaceId.toString()),
            null,
            null,
            "created_at DESC, id DESC"
        )
        return c.use { cursor -> buildList { while (cursor.moveToNext()) add(messageFrom(cursor)) } }
    }

    fun deleteMessage(messageId: Long) {
        saveMessageVersion(messageId, "trash")
        writableDatabase.execSQL(
            "UPDATE spaces SET focus_message_id=NULL WHERE focus_message_id=?",
            arrayOf(messageId)
        )
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("deleted_at", System.currentTimeMillis()) },
            "id=?",
            arrayOf(messageId.toString())
        )
    }

    fun restoreMessage(messageId: Long) {
        writableDatabase.update(
            "messages",
            ContentValues().apply { putNull("deleted_at") },
            "id=?",
            arrayOf(messageId.toString())
        )
    }

    fun hardDeleteMessage(messageId: Long) {
        writableDatabase.delete("message_versions", "message_id=?", arrayOf(messageId.toString()))
        writableDatabase.delete("document_chunks", "message_id=?", arrayOf(messageId.toString()))
        writableDatabase.delete("document_meta", "message_id=?", arrayOf(messageId.toString()))
        writableDatabase.delete("messages", "id=?", arrayOf(messageId.toString()))
    }

    fun listTrash(limit: Int = 100): List<MessageRow> {
        val cursor = readableDatabase.query(
            "messages", null, "deleted_at IS NOT NULL", emptyArray(), null, null,
            "deleted_at DESC", limit.coerceIn(1, 500).toString()
        )
        return cursor.use { c -> buildList { while (c.moveToNext()) add(messageFrom(c)) } }
    }

    fun search(query: String, limit: Int = 12): List<MessageRow> {
        val clean = query.trim()
        if (SmartSearch.normalize(clean).isBlank()) return emptyList()

        val scored = mutableListOf<Pair<Int, MessageRow>>()
        val c = readableDatabase.query("messages", null, "deleted_at IS NULL", null, null, null, "created_at DESC, id DESC")
        c.use { cursor ->
            while (cursor.moveToNext()) {
                val row = messageFrom(cursor)
                val score = SmartSearch.score(
                    query = clean,
                    displayName = row.displayName,
                    tags = row.tags,
                    classification = row.classification,
                    summary = row.summary,
                    text = row.text,
                    ocrText = row.ocrText
                )
                if (score > 0) scored += score to row
            }
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<Int, MessageRow>> { it.first }
                    .thenByDescending { it.second.createdAt }
                    .thenByDescending { it.second.id }
            )
            .take(limit.coerceIn(1, 50))
            .map { it.second }
    }

    fun renameMessageDisplayName(messageId: Long, displayName: String) {
        saveMessageVersion(messageId, "smart_rename")
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("display_name", displayName.take(180)) },
            "id=?",
            arrayOf(messageId.toString())
        )
    }

    fun allMessagesIncludingTrash(): List<MessageRow> {
        val cursor = readableDatabase.query("messages", null, null, null, null, null, "created_at ASC, id ASC")
        return cursor.use { c -> buildList { while (c.moveToNext()) add(messageFrom(c)) } }
    }

    fun setMessageContentHash(messageId: Long, hash: String) {
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("content_hash", hash) },
            "id=?",
            arrayOf(messageId.toString())
        )
    }

    fun findDuplicateByHash(hash: String, excludeMessageId: Long? = null): MessageRow? {
        val where = if (excludeMessageId == null) {
            "content_hash=? AND deleted_at IS NULL"
        } else {
            "content_hash=? AND id<>? AND deleted_at IS NULL"
        }
        val args = if (excludeMessageId == null) arrayOf(hash) else arrayOf(hash, excludeMessageId.toString())
        val cursor = readableDatabase.query("messages", null, where, args, null, null, "created_at DESC", "1")
        return cursor.use { if (it.moveToFirst()) messageFrom(it) else null }
    }

    fun upsertDocumentMeta(meta: DocumentMetaRow) {
        writableDatabase.insertWithOnConflict(
            "document_meta",
            null,
            ContentValues().apply {
                put("message_id", meta.messageId)
                put("smart_title", meta.smartTitle)
                put("doc_type", meta.docType)
                put("organization", meta.organization)
                put("person_names", meta.personNames)
                put("reference_number", meta.referenceNumber)
                put("amount_text", meta.amountText)
                put("currency", meta.currency)
                put("issue_date", meta.issueDate)
                put("due_date", meta.dueDate)
                put("expiry_date", meta.expiryDate)
                put("action_required", if (meta.actionRequired) 1 else 0)
                put("action_text", meta.actionText)
                put("confidence", meta.confidence)
                put("evidence_json", meta.evidenceJson)
                put("extracted_json", meta.extractedJson)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getDocumentMeta(messageId: Long): DocumentMetaRow? {
        val cursor = readableDatabase.query("document_meta", null, "message_id=?", arrayOf(messageId.toString()), null, null, null, "1")
        return cursor.use { if (it.moveToFirst()) documentMetaFrom(it) else null }
    }

    fun listTrackedDocuments(): List<TrackedDocumentRow> {
        val cursor = readableDatabase.rawQuery(
            """
            SELECT d.message_id, m.space_id, m.display_name, d.smart_title,
                   d.due_date, d.expiry_date, d.action_text
            FROM document_meta d
            JOIN messages m ON m.id=d.message_id
            WHERE m.deleted_at IS NULL
              AND ((d.due_date IS NOT NULL AND d.due_date<>'')
                OR (d.expiry_date IS NOT NULL AND d.expiry_date<>''))
            ORDER BY COALESCE(d.due_date, d.expiry_date) ASC
            """.trimIndent(),
            null
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        TrackedDocumentRow(
                            messageId = c.getLong(0),
                            spaceId = c.getLong(1),
                            displayName = if (c.isNull(2)) null else c.getString(2),
                            smartTitle = if (c.isNull(3)) null else c.getString(3),
                            dueDate = if (c.isNull(4)) null else c.getString(4),
                            expiryDate = if (c.isNull(5)) null else c.getString(5),
                            actionText = if (c.isNull(6)) null else c.getString(6)
                        )
                    )
                }
            }
        }
    }

    fun clearGeneratedActionItemsForMessage(messageId: Long) {
        writableDatabase.delete(
            "action_items",
            "message_id=? AND status='open' AND kind IN ('deadline','document_action')",
            arrayOf(messageId.toString())
        )
    }

    fun importActionItem(
        spaceId: Long,
        messageId: Long?,
        kind: String,
        title: String,
        details: String?,
        dueAt: Long?,
        status: String,
        sourceExcerpt: String?,
        createdAt: Long,
        updatedAt: Long
    ): Long = writableDatabase.insert(
        "action_items",
        null,
        ContentValues().apply {
            put("space_id", spaceId)
            if (messageId == null) putNull("message_id") else put("message_id", messageId)
            put("kind", kind.take(60))
            put("title", title.take(180))
            if (details == null) putNull("details") else put("details", details.take(1200))
            if (dueAt == null) putNull("due_at") else put("due_at", dueAt)
            put("status", status.take(30).ifBlank { "open" })
            if (sourceExcerpt == null) putNull("source_excerpt") else put("source_excerpt", sourceExcerpt.take(1200))
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }
    )

    fun createActionItem(
        spaceId: Long,
        messageId: Long?,
        kind: String,
        title: String,
        details: String?,
        dueAt: Long?,
        sourceExcerpt: String?
    ): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insert("action_items", null, ContentValues().apply {
            put("space_id", spaceId)
            if (messageId == null) putNull("message_id") else put("message_id", messageId)
            put("kind", kind.take(60))
            put("title", title.take(180))
            put("details", details?.take(1200))
            if (dueAt == null) putNull("due_at") else put("due_at", dueAt)
            put("status", "open")
            put("source_excerpt", sourceExcerpt?.take(1200))
            put("created_at", now)
            put("updated_at", now)
        })
    }

    fun listOpenActionItems(limit: Int = 100): List<ActionItemRow> {
        val cursor = readableDatabase.query(
            "action_items", null, "status='open'", emptyArray(), null, null,
            "CASE WHEN due_at IS NULL THEN 1 ELSE 0 END, due_at ASC, created_at DESC",
            limit.coerceIn(1, 500).toString()
        )
        return cursor.use { c -> buildList { while (c.moveToNext()) add(actionItemFrom(c)) } }
    }

    fun setActionItemStatus(id: Long, status: String) {
        writableDatabase.update(
            "action_items",
            ContentValues().apply {
                put("status", status.take(30))
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id.toString())
        )
    }

    fun getActionItem(id: Long): ActionItemRow? {
        val cursor = readableDatabase.query("action_items", null, "id=?", arrayOf(id.toString()), null, null, null, "1")
        return cursor.use { if (it.moveToFirst()) actionItemFrom(it) else null }
    }

    fun completeActionItem(id: Long): List<Long> {
        setActionItemStatus(id, "done")
        val cursor = readableDatabase.query(
            "reminders", arrayOf("id"), "condition_action_id=? AND enabled=1",
            arrayOf(id.toString()), null, null, null
        )
        val reminderIds = cursor.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        writableDatabase.update(
            "reminders",
            ContentValues().apply {
                put("enabled", 0)
                putNull("next_fire_at")
            },
            "condition_action_id=?",
            arrayOf(id.toString())
        )
        return reminderIds
    }

    fun replaceDocumentChunks(messageId: Long, chunks: List<String>) {
        writableDatabase.transaction {
            delete("document_chunks", "message_id=?", arrayOf(messageId.toString()))
            val now = System.currentTimeMillis()
            chunks.forEachIndexed { index, text ->
                insert("document_chunks", null, ContentValues().apply {
                    put("message_id", messageId)
                    put("chunk_index", index)
                    put("text", text)
                    put("created_at", now)
                })
            }
        }
    }

    fun setChunkEmbedding(messageId: Long, chunkIndex: Int, embedding: ByteArray) {
        writableDatabase.update(
            "document_chunks",
            ContentValues().apply { put("embedding", embedding) },
            "message_id=? AND chunk_index=?",
            arrayOf(messageId.toString(), chunkIndex.toString())
        )
    }

    fun listDocumentChunks(onlyWithoutEmbedding: Boolean = false): List<DocumentChunkRow> {
        val where = if (onlyWithoutEmbedding) "embedding IS NULL" else null
        val cursor = readableDatabase.query(
            "document_chunks", null, where, null, null, null, "message_id ASC, chunk_index ASC"
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DocumentChunkRow(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            messageId = c.getLong(c.getColumnIndexOrThrow("message_id")),
                            chunkIndex = c.getInt(c.getColumnIndexOrThrow("chunk_index")),
                            text = c.getString(c.getColumnIndexOrThrow("text")),
                            embedding = c.blobOrNull("embedding")
                        )
                    )
                }
            }
        }
    }

    fun saveMessageVersion(messageId: Long, reason: String) {
        val cursor = readableDatabase.query("messages", null, "id=?", arrayOf(messageId.toString()), null, null, null, "1")
        cursor.use {
            if (!it.moveToFirst()) return
            writableDatabase.insert("message_versions", null, ContentValues().apply {
                put("message_id", messageId)
                put("reason", reason.take(60))
                put("text", it.stringOrNull("text"))
                put("display_name", it.stringOrNull("display_name"))
                put("ocr_text", it.stringOrNull("ocr_text"))
                put("classification", it.stringOrNull("classification"))
                put("tags", it.stringOrNull("tags"))
                put("summary", it.stringOrNull("summary"))
                put("created_at", System.currentTimeMillis())
            })
        }
    }
    fun listMessageVersions(messageId: Long, limit: Int = 50): List<MessageVersionRow> {
        val cursor = readableDatabase.query(
            "message_versions",
            null,
            "message_id=?",
            arrayOf(messageId.toString()),
            null,
            null,
            "created_at DESC, id DESC",
            limit.coerceIn(1, 200).toString()
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        MessageVersionRow(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            messageId = c.getLong(c.getColumnIndexOrThrow("message_id")),
                            reason = c.getString(c.getColumnIndexOrThrow("reason")),
                            text = c.stringOrNull("text"),
                            displayName = c.stringOrNull("display_name"),
                            ocrText = c.stringOrNull("ocr_text"),
                            classification = c.stringOrNull("classification"),
                            tags = c.stringOrNull("tags"),
                            summary = c.stringOrNull("summary"),
                            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                        )
                    )
                }
            }
        }
    }

    fun restoreMessageVersion(versionId: Long): Boolean {
        val cursor = readableDatabase.query(
            "message_versions", null, "id=?", arrayOf(versionId.toString()), null, null, null, "1"
        )
        val version = cursor.use {
            if (!it.moveToFirst()) return false
            MessageVersionRow(
                id = it.getLong(it.getColumnIndexOrThrow("id")),
                messageId = it.getLong(it.getColumnIndexOrThrow("message_id")),
                reason = it.getString(it.getColumnIndexOrThrow("reason")),
                text = it.stringOrNull("text"),
                displayName = it.stringOrNull("display_name"),
                ocrText = it.stringOrNull("ocr_text"),
                classification = it.stringOrNull("classification"),
                tags = it.stringOrNull("tags"),
                summary = it.stringOrNull("summary"),
                createdAt = it.getLong(it.getColumnIndexOrThrow("created_at"))
            )
        }
        saveMessageVersion(version.messageId, "before_restore")
        val changed = writableDatabase.update(
            "messages",
            ContentValues().apply {
                put("text", version.text ?: "")
                if (version.displayName == null) putNull("display_name") else put("display_name", version.displayName)
                if (version.ocrText == null) putNull("ocr_text") else put("ocr_text", version.ocrText)
                if (version.classification == null) putNull("classification") else put("classification", version.classification)
                if (version.tags == null) putNull("tags") else put("tags", version.tags)
                if (version.summary == null) putNull("summary") else put("summary", version.summary)
                putNull("deleted_at")
            },
            "id=?",
            arrayOf(version.messageId.toString())
        )
        return changed == 1
    }

    fun importSpace(
        title: String,
        pinned: Boolean,
        archived: Boolean,
        createdAt: Long,
        updatedAt: Long
    ): Long = writableDatabase.insert(
        "spaces",
        null,
        ContentValues().apply {
            put("title", title.trim().ifBlank { "مساحة مستوردة" }.take(120))
            put("pinned", if (pinned) 1 else 0)
            put("archived", if (archived) 1 else 0)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }
    )

    fun importMessage(
        spaceId: Long,
        role: String,
        kind: String,
        text: String,
        filePath: String?,
        mimeType: String?,
        displayName: String?,
        ocrText: String?,
        classification: String?,
        tags: String?,
        summary: String?,
        starred: Boolean,
        createdAt: Long
    ): Long = writableDatabase.insert(
        "messages",
        null,
        ContentValues().apply {
            put("space_id", spaceId)
            put("role", role.take(20))
            put("kind", kind.take(20))
            put("text", text)
            if (filePath == null) putNull("file_path") else put("file_path", filePath)
            if (mimeType == null) putNull("mime_type") else put("mime_type", mimeType)
            if (displayName == null) putNull("display_name") else put("display_name", displayName)
            if (ocrText == null) putNull("ocr_text") else put("ocr_text", ocrText)
            if (classification == null) putNull("classification") else put("classification", classification)
            if (tags == null) putNull("tags") else put("tags", tags)
            if (summary == null) putNull("summary") else put("summary", summary)
            put("starred", if (starred) 1 else 0)
            put("created_at", createdAt)
        }
    )


    fun createReminder(
        spaceId: Long,
        title: String,
        body: String,
        repeatRule: String,
        dayOfWeek: Int?,
        hour: Int?,
        minute: Int?,
        nextFireAt: Long?,
        conditionActionId: Long? = null
    ): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insert("reminders", null, ContentValues().apply {
            put("space_id", spaceId)
            put("title", title.take(100))
            put("body", body.take(500))
            put("repeat_rule", repeatRule)
            if (dayOfWeek == null) putNull("day_of_week") else put("day_of_week", dayOfWeek)
            if (hour == null) putNull("hour") else put("hour", hour)
            if (minute == null) putNull("minute") else put("minute", minute)
            if (nextFireAt == null) putNull("next_fire_at") else put("next_fire_at", nextFireAt)
            if (conditionActionId == null) putNull("condition_action_id") else put("condition_action_id", conditionActionId)
            put("enabled", 1)
            put("created_at", now)
        })
    }

    fun importReminder(
        spaceId: Long,
        title: String,
        body: String,
        repeatRule: String,
        dayOfWeek: Int?,
        hour: Int?,
        minute: Int?,
        nextFireAt: Long?,
        enabled: Boolean,
        createdAt: Long
    ): Long = writableDatabase.insert(
        "reminders",
        null,
        ContentValues().apply {
            put("space_id", spaceId)
            put("title", title.take(100))
            put("body", body.take(500))
            put("repeat_rule", repeatRule.take(20).ifBlank { "none" })
            if (dayOfWeek == null) putNull("day_of_week") else put("day_of_week", dayOfWeek)
            if (hour == null) putNull("hour") else put("hour", hour)
            if (minute == null) putNull("minute") else put("minute", minute)
            if (nextFireAt == null) putNull("next_fire_at") else put("next_fire_at", nextFireAt)
            put("enabled", if (enabled) 1 else 0)
            put("created_at", createdAt)
        }
    )

    fun getReminder(id: Long): ReminderRow? {
        val c = readableDatabase.query("reminders", null, "id=?", arrayOf(id.toString()), null, null, null, "1")
        return c.use { if (it.moveToFirst()) reminderFrom(it) else null }
    }

    fun listActiveReminders(): List<ReminderRow> {
        val c = readableDatabase.query("reminders", null, "enabled=1", emptyArray(), null, null, "next_fire_at ASC")
        return c.use { cursor -> buildList { while (cursor.moveToNext()) add(reminderFrom(cursor)) } }
    }

    fun updateReminderNextFire(id: Long, nextFireAt: Long?) {
        writableDatabase.update("reminders", ContentValues().apply {
            if (nextFireAt == null) putNull("next_fire_at") else put("next_fire_at", nextFireAt)
        }, "id=?", arrayOf(id.toString()))
    }

    fun disableReminder(id: Long) {
        writableDatabase.update("reminders", ContentValues().apply {
            put("enabled", 0)
            putNull("next_fire_at")
        }, "id=?", arrayOf(id.toString()))
    }

    fun tryMarkReminderDelivered(id: Long, scheduledAt: Long, deliveredAt: Long): Boolean {
        val cutoff = scheduledAt - 60_000L
        val changed = writableDatabase.update(
            "reminders",
            ContentValues().apply { put("delivered_at", deliveredAt) },
            "id=? AND enabled=1 AND (delivered_at IS NULL OR delivered_at < ?)",
            arrayOf(id.toString(), cutoff.toString())
        )
        return changed == 1
    }

    private fun documentMetaFrom(c: Cursor) = DocumentMetaRow(
        messageId = c.getLong(c.getColumnIndexOrThrow("message_id")),
        smartTitle = c.stringOrNull("smart_title"),
        docType = c.stringOrNull("doc_type"),
        organization = c.stringOrNull("organization"),
        personNames = c.stringOrNull("person_names"),
        referenceNumber = c.stringOrNull("reference_number"),
        amountText = c.stringOrNull("amount_text"),
        currency = c.stringOrNull("currency"),
        issueDate = c.stringOrNull("issue_date"),
        dueDate = c.stringOrNull("due_date"),
        expiryDate = c.stringOrNull("expiry_date"),
        actionRequired = c.getInt(c.getColumnIndexOrThrow("action_required")) == 1,
        actionText = c.stringOrNull("action_text"),
        confidence = c.doubleOrNull("confidence"),
        evidenceJson = c.stringOrNull("evidence_json"),
        extractedJson = c.stringOrNull("extracted_json"),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun actionItemFrom(c: Cursor) = ActionItemRow(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        spaceId = c.getLong(c.getColumnIndexOrThrow("space_id")),
        messageId = c.longOrNull("message_id"),
        kind = c.getString(c.getColumnIndexOrThrow("kind")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        details = c.stringOrNull("details"),
        dueAt = c.longOrNull("due_at"),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        sourceExcerpt = c.stringOrNull("source_excerpt"),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun reminderFrom(c: Cursor) = ReminderRow(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        spaceId = c.getLong(c.getColumnIndexOrThrow("space_id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        body = c.getString(c.getColumnIndexOrThrow("body")),
        repeatRule = c.getString(c.getColumnIndexOrThrow("repeat_rule")),
        dayOfWeek = c.intOrNull("day_of_week"),
        hour = c.intOrNull("hour"),
        minute = c.intOrNull("minute"),
        nextFireAt = c.longOrNull("next_fire_at"),
        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
        deliveredAt = c.longOrNull("delivered_at"),
        conditionActionId = c.longOrNull("condition_action_id"),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )

    private fun Cursor.intOrNull(name: String): Int? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getInt(i)
    }

    private fun Cursor.longOrNull(name: String): Long? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getLong(i)
    }

    private fun Cursor.doubleOrNull(name: String): Double? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getDouble(i)
    }

    private fun Cursor.blobOrNull(name: String): ByteArray? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getBlob(i)
    }

    private fun spaceFrom(c: Cursor) = SpaceRow(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        pinned = c.getInt(c.getColumnIndexOrThrow("pinned")) == 1,
        archived = c.getInt(c.getColumnIndexOrThrow("archived")) == 1,
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun messageFrom(c: Cursor) = MessageRow(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        spaceId = c.getLong(c.getColumnIndexOrThrow("space_id")),
        role = c.getString(c.getColumnIndexOrThrow("role")),
        kind = c.getString(c.getColumnIndexOrThrow("kind")),
        text = c.getString(c.getColumnIndexOrThrow("text")) ?: "",
        filePath = c.stringOrNull("file_path"),
        mimeType = c.stringOrNull("mime_type"),
        displayName = c.stringOrNull("display_name"),
        ocrText = c.stringOrNull("ocr_text"),
        classification = c.stringOrNull("classification"),
        tags = c.stringOrNull("tags"),
        summary = c.stringOrNull("summary"),
        starred = c.getInt(c.getColumnIndexOrThrow("starred")) == 1,
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )

    private fun Cursor.stringOrNull(name: String): String? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getString(i)
    }
}

private object DatabaseUtilsCompat {
    fun longForQuery(db: SQLiteDatabase, sql: String, args: Array<String>): Long {
        val c = db.rawQuery(sql, args)
        return c.use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }
}
