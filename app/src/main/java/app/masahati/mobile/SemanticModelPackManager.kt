package app.masahati.mobile

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object SemanticModelSpec {
    const val FILE_NAME = "embedding_gemma_int4int8.task"
    const val URL = "https://storage.googleapis.com/mediapipe-models/text_embedder/embedding_gemma/int4int8/latest/embedding_gemma.task"
    const val EXPECTED_BYTES = 183_816_181L
    const val EXPECTED_MD5 = "dabc0e55b47b898a38472d5d99f37892"
}

class SemanticModelPackManager(private val context: Context) {
    private val directory: File
        get() = File(context.filesDir, "semantic-models").apply { mkdirs() }

    fun modelFile(): File = File(directory, SemanticModelSpec.FILE_NAME)
    private fun markerFile(): File = File(directory, SemanticModelSpec.FILE_NAME + ".verified")

    fun isInstalled(): Boolean {
        val file = modelFile()
        val marker = markerFile()
        return file.isFile &&
            file.length() == SemanticModelSpec.EXPECTED_BYTES &&
            marker.isFile &&
            marker.readText().trim().equals(SemanticModelSpec.EXPECTED_MD5, ignoreCase = true)
    }

    fun delete(): Boolean {
        val a = modelFile().let { !it.exists() || it.delete() }
        val b = File(modelFile().absolutePath + ".part").let { !it.exists() || it.delete() }
        val c = markerFile().let { !it.exists() || it.delete() }
        return a && b && c
    }

    fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        if (isInstalled()) return modelFile()
        val finalFile = modelFile()
        val partial = File(finalFile.absolutePath + ".part")
        var existing = partial.takeIf { it.exists() }?.length() ?: 0L
        if (existing > SemanticModelSpec.EXPECTED_BYTES) {
            partial.delete()
            existing = 0L
        }

        val connection = (URL(SemanticModelSpec.URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Masahati-Alpha/0.1")
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            connection.connect()
            val code = connection.responseCode
            if (existing > 0L && code == HttpURLConnection.HTTP_OK) {
                partial.delete()
                existing = 0L
            } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                error("HTTP $code")
            }

            connection.inputStream.use { input ->
                RandomAccessFile(partial, "rw").use { out ->
                    if (existing > 0L) out.seek(existing) else out.setLength(0L)
                    val buffer = ByteArray(1024 * 1024)
                    var downloaded = existing
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, SemanticModelSpec.EXPECTED_BYTES)
                    }
                }
            }

            if (partial.length() != SemanticModelSpec.EXPECTED_BYTES) {
                error("حجم نموذج الذاكرة الدلالية غير صحيح")
            }
            val digest = md5(partial)
            if (!digest.equals(SemanticModelSpec.EXPECTED_MD5, ignoreCase = true)) {
                partial.delete()
                error("فشل التحقق من نموذج الذاكرة الدلالية")
            }
            if (finalFile.exists()) finalFile.delete()
            if (!partial.renameTo(finalFile)) {
                partial.copyTo(finalFile, overwrite = true)
                partial.delete()
            }
            markerFile().writeText(SemanticModelSpec.EXPECTED_MD5)
            return finalFile
        } finally {
            connection.disconnect()
        }
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
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
