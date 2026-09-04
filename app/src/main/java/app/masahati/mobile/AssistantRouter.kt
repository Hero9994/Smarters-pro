package app.masahati.mobile

import android.content.Context
import org.json.JSONObject
import java.io.Closeable
import java.io.File

class AssistantRouter(context: Context) : Closeable {
    private val modelManager = AiModelManager(context)
    private val localEngine = OnDeviceAiEngine(context, modelManager)

    @Volatile
    private var localFailureAt: Long = 0L

    fun hasLocalModel(): Boolean = modelManager.isInstalled()

    fun isModelDownloading(): Boolean = modelManager.isDownloading()

    fun downloadLocalModel(progress: (Long, Long) -> Unit): Result<File> =
        modelManager.download(progress)

    fun verifyLocalModel(): Boolean = modelManager.verifyInstalled()

    fun clearLocalModel() {
        localEngine.close()
        modelManager.clearModel()
    }

    fun prewarm(): Boolean {
        if (!hasLocalModel()) return false
        return runCatching { localEngine.prewarm() }.getOrElse {
            localFailureAt = System.currentTimeMillis()
            false
        }
    }

    /**
     * Stable deterministic reminder parsing happens before this router in MainActivity.
     * This router handles semantic reasoning without rewriting reminder behavior.
     */
    fun analyze(
        body: JSONObject,
        spaceTitle: String,
        recent: List<MessageRow>,
        cloud: (JSONObject) -> JSONObject?
    ): JSONObject {
        val text = body.optString("text").trim()
        val deterministicFirst = looksLikeDeterministicAppCommand(text)
        val localReady = hasLocalModel() && !localInCooldown()

        if (localReady && !deterministicFirst) {
            local(body)?.let { if (isUseful(it)) return it }
        }

        val remote = runCatching { cloud(body) }.getOrNull()
        if (remote?.optBoolean("ok", false) == true) {
            val engineName = remote.optString("engine")
            val genericEdgeFallback =
                engineName.contains("fallback", ignoreCase = true) && isGeneric(remote.optString("reply"))
            if (!genericEdgeFallback || !localReady) {
                return remote
            }
        }

        if (localReady && deterministicFirst) {
            local(body)?.let { if (isUseful(it)) return it }
        }

        return LocalAssistantFallback.analyze(text, spaceTitle, recent)
    }

    private fun local(body: JSONObject): JSONObject? {
        return try {
            localEngine.analyze(body)
        } catch (_: Throwable) {
            localFailureAt = System.currentTimeMillis()
            null
        }
    }

    private fun localInCooldown(): Boolean =
        localFailureAt > 0L && System.currentTimeMillis() - localFailureAt < LOCAL_FAILURE_COOLDOWN_MS

    private fun looksLikeDeterministicAppCommand(text: String): Boolean =
        Regex(
            "^(?:وين|أين|اين|ابحث|دور|فتش|أرشف|ارشف|ثبّت|ثبت|انقل|نقل|أنشئ|انشئ|افتح|سمّي|سمي|غيّر اسم|غير اسم)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text.trim())

    private fun isUseful(result: JSONObject): Boolean {
        if (!result.optBoolean("ok", false)) return false
        val reply = result.optString("reply").trim()
        return reply.length >= 8 && !isGeneric(reply)
    }

    private fun isGeneric(reply: String): Boolean {
        val clean = reply.trim()
        return clean == "فهمت المحتوى وحفظته كملاحظة قابلة للبحث." ||
            clean == "فهمت المحتوى وحفظته." ||
            clean == "فهمت المحتوى وحفظته بشكل قابل للبحث." ||
            clean == "حفظت المحتوى."
    }

    override fun close() {
        localEngine.close()
    }

    companion object {
        private const val LOCAL_FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
