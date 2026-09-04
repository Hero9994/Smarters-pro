package app.masahati.mobile.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import org.json.JSONArray
import org.json.JSONObject

class LiteRtLmLocalEngine(
    context: Context,
    private val spec: LocalModelSpec = LocalModelCatalog.default
) : LocalAiEngine {
    private val appContext = context.applicationContext
    private val packs = LocalModelPackManager(appContext)
    private val lock = Any()
    @Volatile private var engine: Engine? = null

    override fun isReady(): Boolean = packs.isInstalled(spec)

    override fun generate(request: MasahatiAiRequest): JSONObject? {
        if (!isReady()) return null
        return synchronized(lock) {
            val runtime = engine ?: createEngine().also { engine = it }
            val conversation = runtime.createConversation(ConversationConfig())
            try {
                val response = conversation.sendMessage(Message.user(MasahatiLocalPrompt.build(request)))
                parseJson(response.toString())
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { conversation.close() }
            }
        }
    }

    private fun createEngine(): Engine {
        val file = packs.modelFile(spec)
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = Backend.CPU(),
            maxNumTokens = spec.maxTokens
        )
        return Engine(config).also { it.initialize() }
    }

    override fun close() {
        synchronized(lock) {
            val current = engine
            engine = null
            if (current != null) runCatching { current.close() }
        }
    }

    private fun parseJson(raw: String): JSONObject? {
        val clean = raw
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val parsed = runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull() ?: return null
        if (!parsed.has("reply")) return null
        if (!parsed.has("actions")) parsed.put("actions", JSONArray())
        if (!parsed.has("labels")) parsed.put("labels", JSONArray())
        if (!parsed.has("keywords")) parsed.put("keywords", JSONArray())
        if (!parsed.has("classification")) parsed.put("classification", "other")
        if (!parsed.has("summary")) parsed.put("summary", parsed.optString("reply").trim().take(420))
        parsed.put("ok", true)
        parsed.put("engine", "litert-lm-local")
        parsed.put("model", spec.id)
        return parsed
    }
}
