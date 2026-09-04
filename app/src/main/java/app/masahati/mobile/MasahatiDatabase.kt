package app.masahati.mobile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
    val createdAt: Long
)

class MasahatiDatabase(context: Context) : SQLiteOpenHelper(context, "masahati_v05.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE spaces(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              pinned INTEGER NOT NULL DEFAULT 0,
              archived INTEGER NOT NULL DEFAULT 0,
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
              created_at INTEGER NOT NULL,
              FOREIGN KEY(space_id) REFERENCES spaces(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_space_created ON messages(space_id, created_at)")
        db.execSQL("CREATE INDEX idx_spaces_archived_pinned ON spaces(archived, pinned, updated_at)")
        createReminderTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DELETE FROM spaces WHERE title IN ('ملاحظات','يومي','أوراقي','أفكار المشروع') AND NOT EXISTS (SELECT 1 FROM messages WHERE messages.space_id = spaces.id)")
        }
        if (oldVersion < 3) createReminderTable(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
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
              created_at INTEGER NOT NULL,
              FOREIGN KEY(space_id) REFERENCES spaces(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reminders_active_next ON reminders(enabled, next_fire_at)")
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
    ): Long = insertMessage(
        spaceId = spaceId,
        role = role,
        kind = "file",
        text = text,
        filePath = filePath,
        mimeType = mimeType,
        displayName = displayName,
        ocrText = ocrText
    )

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
        writableDatabase.update("messages", ContentValues().apply { put("ocr_text", ocrText) }, "id=?", arrayOf(messageId.toString()))
    }

    fun updateAi(messageId: Long, classification: String?, tags: String?, summary: String?, aiJson: String) {
        writableDatabase.update("messages", ContentValues().apply {
            put("classification", classification)
            put("tags", tags)
            put("summary", summary)
            put("ai_json", aiJson)
        }, "id=?", arrayOf(messageId.toString()))
    }

    fun listMessages(spaceId: Long): List<MessageRow> {
        val c = readableDatabase.query(
            "messages", null, "space_id=?", arrayOf(spaceId.toString()), null, null, "created_at ASC, id ASC"
        )
        return c.use { cursor -> buildList { while (cursor.moveToNext()) add(messageFrom(cursor)) } }
    }

    fun recentForAi(spaceId: Long, limit: Int = 20): List<MessageRow> {
        val c = readableDatabase.query(
            "messages",
            null,
            "space_id=? AND (text<>'' OR (ocr_text IS NOT NULL AND ocr_text<>'') OR (summary IS NOT NULL AND summary<>'') OR (display_name IS NOT NULL AND display_name<>''))",
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
            "messages", null, "space_id=? AND role='user'", arrayOf(spaceId.toString()), null, null,
            "created_at DESC, id DESC", "1"
        )
        return c.use { if (it.moveToFirst()) messageFrom(it) else null }
    }

    fun lastFileMessage(spaceId: Long): MessageRow? {
        val c = readableDatabase.query(
            "messages", null, "space_id=? AND kind='file'", arrayOf(spaceId.toString()), null, null,
            "created_at DESC, id DESC", "1"
        )
        return c.use { if (it.moveToFirst()) messageFrom(it) else null }
    }

    fun renameFileMessage(messageId: Long, displayName: String) {
        val clean = displayName.trim().take(180)
        if (clean.isBlank()) return
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("display_name", clean) },
            "id=? AND kind='file'",
            arrayOf(messageId.toString())
        )
    }

    fun moveMessage(messageId: Long, targetSpaceId: Long) {
        writableDatabase.update("messages", ContentValues().apply { put("space_id", targetSpaceId) }, "id=?", arrayOf(messageId.toString()))
        writableDatabase.update("spaces", ContentValues().apply { put("updated_at", System.currentTimeMillis()) }, "id=?", arrayOf(targetSpaceId.toString()))
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
        writableDatabase.delete("messages", "id=?", arrayOf(messageId.toString()))
    }

    fun search(query: String, limit: Int = 12): List<MessageRow> {
        val clean = query.trim()
        if (SmartSearch.normalize(clean).isBlank()) return emptyList()

        val scored = mutableListOf<Pair<Int, MessageRow>>()
        val c = readableDatabase.query("messages", null, null, null, null, null, "created_at DESC, id DESC")
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

    fun createReminder(
        spaceId: Long,
        title: String,
        body: String,
        repeatRule: String,
        dayOfWeek: Int?,
        hour: Int?,
        minute: Int?,
        nextFireAt: Long?
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
            put("enabled", 1)
            put("created_at", now)
        })
    }

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
