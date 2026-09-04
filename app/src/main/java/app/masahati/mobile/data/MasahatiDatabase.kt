package app.masahati.mobile.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class SpaceSummary(val id: Long,val title: String,val pinned: Boolean,val archived: Boolean,val lastPreview: String?,val lastActivityAt: Long)
data class SpaceRecord(val id: Long,val title: String,val pinned: Boolean,val archived: Boolean)
data class MessageRecord(val id: Long,val spaceId: Long,val type: String,val text: String?,val fileName: String?,val filePath: String?,val mimeType: String?,val createdAt: Long)

class MasahatiDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE spaces (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,pinned INTEGER NOT NULL DEFAULT 0,archived INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT,space_id INTEGER NOT NULL,type TEXT NOT NULL CHECK(type IN ('text','file')),text TEXT,file_name TEXT,file_path TEXT,mime_type TEXT,created_at INTEGER NOT NULL,FOREIGN KEY(space_id) REFERENCES spaces(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX idx_spaces_updated ON spaces(archived, pinned, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_messages_space_created ON messages(space_id, created_at, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun listSpaces(query: String="", archived: Boolean=false): List<SpaceSummary> {
        val normalized=query.trim(); val like="%$normalized%"
        val sql="""
            SELECT s.id,s.title,s.pinned,s.archived,
              (SELECT CASE WHEN m.type='file' THEN '📎 ' || COALESCE(m.file_name,'ملف') ELSE m.text END FROM messages m WHERE m.space_id=s.id ORDER BY m.created_at DESC,m.id DESC LIMIT 1) AS last_preview,
              COALESCE((SELECT m.created_at FROM messages m WHERE m.space_id=s.id ORDER BY m.created_at DESC,m.id DESC LIMIT 1),s.updated_at) AS last_activity
            FROM spaces s
            WHERE s.archived=? AND (?='' OR s.title LIKE ? OR EXISTS(SELECT 1 FROM messages sm WHERE sm.space_id=s.id AND (COALESCE(sm.text,'') LIKE ? OR COALESCE(sm.file_name,'') LIKE ?)))
            ORDER BY s.pinned DESC,last_activity DESC,s.id DESC
        """.trimIndent()
        readableDatabase.rawQuery(sql,arrayOf(if(archived)"1" else "0",normalized,like,like,like)).use { c ->
            val out=mutableListOf<SpaceSummary>()
            while(c.moveToNext()) out+=SpaceSummary(c.getLong(0),c.getString(1),c.getInt(2)==1,c.getInt(3)==1,c.getString(4),c.getLong(5))
            return out
        }
    }

    fun getSpace(spaceId: Long): SpaceRecord? {
        readableDatabase.query("spaces",arrayOf("id","title","pinned","archived"),"id=?",arrayOf(spaceId.toString()),null,null,null,"1").use { c ->
            if(!c.moveToFirst()) return null
            return SpaceRecord(c.getLong(0),c.getString(1),c.getInt(2)==1,c.getInt(3)==1)
        }
    }

    fun createSpace(title: String): Long {
        val clean=title.trim(); require(clean.isNotEmpty()); val now=System.currentTimeMillis()
        return writableDatabase.insertOrThrow("spaces",null,ContentValues().apply { put("title",clean);put("pinned",0);put("archived",0);put("created_at",now);put("updated_at",now) })
    }

    fun renameSpace(spaceId: Long,title: String) { val clean=title.trim();require(clean.isNotEmpty());writableDatabase.update("spaces",ContentValues().apply{put("title",clean);put("updated_at",System.currentTimeMillis())},"id=?",arrayOf(spaceId.toString())) }
    fun setPinned(spaceId: Long,pinned: Boolean) { writableDatabase.update("spaces",ContentValues().apply{put("pinned",if(pinned)1 else 0);put("updated_at",System.currentTimeMillis())},"id=?",arrayOf(spaceId.toString())) }
    fun setArchived(spaceId: Long,archived: Boolean) { writableDatabase.update("spaces",ContentValues().apply{put("archived",if(archived)1 else 0);put("updated_at",System.currentTimeMillis())},"id=?",arrayOf(spaceId.toString())) }

    fun deleteSpace(spaceId: Long) {
        val files=mutableListOf<String>()
        readableDatabase.query("messages",arrayOf("file_path"),"space_id=? AND file_path IS NOT NULL",arrayOf(spaceId.toString()),null,null,null).use { c -> while(c.moveToNext()) c.getString(0)?.let(files::add) }
        writableDatabase.delete("spaces","id=?",arrayOf(spaceId.toString()))
        files.forEach { runCatching { File(it).delete() } }
    }

    fun listMessages(spaceId: Long): List<MessageRecord> {
        readableDatabase.query("messages",arrayOf("id","space_id","type","text","file_name","file_path","mime_type","created_at"),"space_id=?",arrayOf(spaceId.toString()),null,null,"created_at ASC,id ASC").use { c ->
            val out=mutableListOf<MessageRecord>()
            while(c.moveToNext()) out+=MessageRecord(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getLong(7))
            return out
        }
    }

    fun addTextMessage(spaceId: Long,text: String): Long {
        val clean=text.trim();require(clean.isNotEmpty());val now=System.currentTimeMillis()
        val id=writableDatabase.insertOrThrow("messages",null,ContentValues().apply{put("space_id",spaceId);put("type","text");put("text",clean);put("created_at",now)})
        touchSpace(spaceId,now);return id
    }

    fun addFileMessage(spaceId: Long,fileName: String,filePath: String,mimeType: String?): Long {
        val now=System.currentTimeMillis()
        val id=writableDatabase.insertOrThrow("messages",null,ContentValues().apply{put("space_id",spaceId);put("type","file");put("file_name",fileName);put("file_path",filePath);put("mime_type",mimeType);put("created_at",now)})
        touchSpace(spaceId,now);return id
    }

    fun deleteMessage(message: MessageRecord) { writableDatabase.delete("messages","id=?",arrayOf(message.id.toString()));message.filePath?.let{runCatching{File(it).delete()}};touchSpace(message.spaceId,System.currentTimeMillis()) }
    private fun touchSpace(spaceId: Long,timestamp: Long) { writableDatabase.update("spaces",ContentValues().apply{put("updated_at",timestamp)},"id=?",arrayOf(spaceId.toString())) }

    companion object { private const val DB_NAME="masahati.db";private const val DB_VERSION=1 }
}
