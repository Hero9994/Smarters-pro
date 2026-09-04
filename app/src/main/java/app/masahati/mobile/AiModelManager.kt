package app.masahati.mobile

import android.content.Context
import android.os.storage.StorageManager
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class AiModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val modelDir = File(appContext.filesDir, "ai").apply { mkdirs() }
    private val finalFile = File(modelDir, MODEL_FILE_NAME)
    private val partialFile = File(modelDir, "$MODEL_FILE_NAME.part")
    private val downloading = AtomicBoolean(false)

    fun modelFile(): File = finalFile

    fun isInstalled(): Boolean {
        return prefs.getBoolean(KEY_VERIFIED, false) &&
            finalFile.isFile &&
            finalFile.length() == MODEL_SIZE_BYTES
    }

    fun verifyInstalled(): Boolean {
        if (!finalFile.isFile || finalFile.length() != MODEL_SIZE_BYTES) {
            prefs.edit { putBoolean(KEY_VERIFIED, false) }
            return false
        }
        val ok = sha256(finalFile).equals(MODEL_SHA256, ignoreCase = true)
        prefs.edit { putBoolean(KEY_VERIFIED, ok) }
        return ok
    }

    fun isDownloading(): Boolean = downloading.get()

    /**
     * Blocking download. Call from a background thread.
     * Supports resuming a partially downloaded file when the server accepts byte ranges.
     */
    fun download(progress: (downloaded: Long, total: Long) -> Unit): Result<File> {
        if (!downloading.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("Model download already running"))
        }
        return try {
            prefs.edit { putBoolean(KEY_VERIFIED, false) }
            modelDir.mkdirs()

            var existing = partialFile.takeIf { it.isFile }?.length() ?: 0L
            if (existing < 0L || existing >= MODEL_SIZE_BYTES) {
                partialFile.delete()
                existing = 0L
            }

            val bytesNeeded = (MODEL_SIZE_BYTES - existing).coerceAtLeast(0L)
            val reserve = 512L * 1024L * 1024L
            val storageManager = appContext.getSystemService(StorageManager::class.java)
            val allocatableBytes = storageManager.getAllocatableBytes(StorageManager.UUID_DEFAULT)
            if (allocatableBytes < bytesNeeded + reserve) {
                throw IllegalStateException("لا توجد مساحة تخزين كافية لتنزيل نموذج الذكاء المحلي")
            }

            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "Masahati-Android/0.8")
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }

            val code = connection.responseCode
            val append = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (code !in 200..299) {
                connection.disconnect()
                throw IllegalStateException("Model server returned HTTP $code")
            }
            if (!append && existing > 0L) {
                partialFile.delete()
                existing = 0L
            }

            val start = if (append) existing else 0L
            connection.inputStream.use { input ->
                FileOutputStream(partialFile, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = start
                    progress(downloaded, MODEL_SIZE_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded > MODEL_SIZE_BYTES + 1024L) {
                            throw IllegalStateException("Downloaded model is larger than expected")
                        }
                        progress(downloaded, MODEL_SIZE_BYTES)
                    }
                    output.fd.sync()
                }
            }
            connection.disconnect()

            if (partialFile.length() != MODEL_SIZE_BYTES) {
                throw IllegalStateException(
                    "Incomplete model: ${partialFile.length()} / $MODEL_SIZE_BYTES bytes"
                )
            }
            val digest = sha256(partialFile)
            if (!digest.equals(MODEL_SHA256, ignoreCase = true)) {
                partialFile.delete()
                throw IllegalStateException("Model checksum verification failed")
            }

            if (finalFile.exists() && !finalFile.delete()) {
                throw IllegalStateException("Cannot replace existing model")
            }
            if (!partialFile.renameTo(finalFile)) {
                partialFile.copyTo(finalFile, overwrite = true)
                partialFile.delete()
            }
            prefs.edit { putBoolean(KEY_VERIFIED, true) }
            Result.success(finalFile)
        } catch (t: Throwable) {
            prefs.edit { putBoolean(KEY_VERIFIED, false) }
            Result.failure(t)
        } finally {
            downloading.set(false)
        }
    }

    fun clearModel() {
        prefs.edit { putBoolean(KEY_VERIFIED, false) }
        partialFile.delete()
        finalFile.delete()
    }

    companion object {
        const val MODEL_FILE_NAME = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"
        const val MODEL_SIZE_BYTES = 977_184_032L
        const val MODEL_SHA256 = "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm?download=true"

        private const val PREFS = "masahati_local_ai"
        private const val KEY_VERIFIED = "model_verified"

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    md.update(buffer, 0, count)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
