package app.masahati.mobile.ai

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class LocalModelPackManager(private val context: Context) {
    private val validated = mutableSetOf<String>()

    private val modelDir: File
        get() = File(context.filesDir, "ai-models").apply { mkdirs() }

    fun modelFile(spec: LocalModelSpec = LocalModelCatalog.default): File =
        File(modelDir, spec.fileName)

    fun isInstalled(spec: LocalModelSpec = LocalModelCatalog.default): Boolean {
        val file = modelFile(spec)
        if (!file.isFile || file.length() < spec.expectedBytes / 2) return false
        synchronized(validated) {
            if (spec.id in validated) return true
        }
        val valid = runCatching { sha256(file).equals(spec.sha256, ignoreCase = true) }.getOrDefault(false)
        if (valid) synchronized(validated) { validated += spec.id }
        return valid
    }

    fun delete(spec: LocalModelSpec = LocalModelCatalog.default): Boolean {
        val file = modelFile(spec)
        val partial = File(file.absolutePath + ".part")
        val a = !file.exists() || file.delete()
        val b = !partial.exists() || partial.delete()
        synchronized(validated) { validated -= spec.id }
        return a && b
    }

    fun download(
        spec: LocalModelSpec = LocalModelCatalog.default,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        val finalFile = modelFile(spec)
        if (isInstalled(spec)) return finalFile

        val partial = File(finalFile.absolutePath + ".part")
        var existing = if (partial.exists()) partial.length() else 0L

        val connection = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Masahati-Android/0.7")
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            connection.connect()
            val code = connection.responseCode
            if (existing > 0L && code == HttpURLConnection.HTTP_OK) {
                partial.delete()
                existing = 0L
            } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("Model download failed: HTTP $code")
            }

            val contentLength = connection.contentLengthLong.coerceAtLeast(0L)
            val total = if (code == HttpURLConnection.HTTP_PARTIAL) existing + contentLength else contentLength

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
                        onProgress(downloaded, total)
                    }
                }
            }

            val digest = sha256(partial)
            if (!digest.equals(spec.sha256, ignoreCase = true)) {
                throw IllegalStateException("Model checksum mismatch")
            }
            if (finalFile.exists()) finalFile.delete()
            if (!partial.renameTo(finalFile)) {
                partial.copyTo(finalFile, overwrite = true)
                partial.delete()
            }
            synchronized(validated) { validated += spec.id }
            return finalFile
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
