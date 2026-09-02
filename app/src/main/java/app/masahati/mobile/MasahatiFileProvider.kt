package app.masahati.mobile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.net.URLConnection

class MasahatiFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Read-only provider" }
        return ParcelFileDescriptor.open(resolveFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = URLConnection.guessContentTypeFromName(resolveFile(uri).name) ?: "application/octet-stream"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file=resolveFile(uri)
        val columns=projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        val cursor=MatrixCursor(columns.toTypedArray(),1)
        val row=cursor.newRow()
        columns.forEach { column -> when(column) { OpenableColumns.DISPLAY_NAME -> row.add(file.name); OpenableColumns.SIZE -> row.add(file.length()); else -> row.add(null) } }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")

    private fun resolveFile(uri: Uri): File {
        val ctx=requireNotNull(context)
        val fileName=uri.lastPathSegment ?: throw IllegalArgumentException("Missing file")
        val root=File(ctx.filesDir,ATTACHMENTS_DIR).canonicalFile
        val target=File(root,fileName).canonicalFile
        require(target.path.startsWith(root.path+File.separator)) { "Invalid file path" }
        require(target.isFile) { "File not found" }
        return target
    }

    companion object {
        private const val ATTACHMENTS_DIR="attachments"
        fun uriFor(context: android.content.Context,file: File): Uri {
            val root=File(context.filesDir,ATTACHMENTS_DIR).canonicalFile
            val target=file.canonicalFile
            require(target.parentFile==root) { "File is outside attachment store" }
            return Uri.Builder().scheme("content").authority(context.packageName+".files").appendPath("attachment").appendPath(target.name).build()
        }
    }
}
