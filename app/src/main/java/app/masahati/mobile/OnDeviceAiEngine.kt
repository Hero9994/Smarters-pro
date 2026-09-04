package app.masahati.mobile

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import org.json.JSONObject
import java.io.Closeable

class OnDeviceAiEngine(
    context: Context,
    private val modelManager: AiModelManager
) : Closeable {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var engine: Engine? = null

    fun isAvailable(): Boolean = modelManager.isInstalled()

    fun prewarm(): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            ensureInitialized()
            true
        }.getOrDefault(false)
    }

    fun analyze(body: JSONObject): JSONObject? {
        if (!isAvailable()) return null
        val userText = body.optString("text").trim()
        if (userText.isBlank()) return null

        return synchronized(lock) {
            val runtime = ensureInitialized()
            val conversation = runtime.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                    samplerConfig = SamplerConfig(
                        topK = 20,
                        topP = 0.88,
                        temperature = 0.18,
                        seed = 7
                    ),
                    automaticToolCalling = false,
                    maxOutputToken = 700,
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                )
            )
            try {
                val raw = conversation.sendMessage(
                    buildPrompt(body),
                    maxOutputToken = 700,
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                ).toString()
                AgentResultSanitizer.parse(raw, userText)
            } finally {
                runCatching { conversation.close() }
            }
        }
    }

    private fun ensureInitialized(): Engine {
        engine?.let { if (it.isInitialized()) return it }
        return synchronized(lock) {
            engine?.let { if (it.isInitialized()) return@synchronized it }

            val modelPath = modelManager.modelFile().absolutePath
            val cacheDir = FilePaths.aiCache(appContext)

            fun initializeWith(backend: Backend): Engine {
                val created = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        maxNumTokens = 4096,
                        cacheDir = cacheDir
                    )
                )
                try {
                    created.initialize()
                    return created
                } catch (t: Throwable) {
                    runCatching { if (created.isInitialized()) created.close() }
                    throw t
                }
            }

            val created = runCatching {
                initializeWith(Backend.GPU())
            }.getOrElse {
                initializeWith(
                    Backend.CPU(
                        threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                    )
                )
            }
            engine = created
            created
        }
    }

    private fun buildPrompt(body: JSONObject): String {
        val text = body.optString("text").trim().take(2200)
        val spaceTitle = body.optString("spaceTitle").trim().take(120)
        val now = body.optString("now").trim().take(100)
        val timezone = body.optString("timezone").trim().take(80)
        val state = body.optJSONObject("appState")
        val currentDoc = state?.optJSONObject("currentDocument")
        val recent = body.optJSONArray("recent")

        val b = StringBuilder(7000)
        b.append("اسم المساحة: ").append(spaceTitle).append('\n')
        b.append("الوقت الحالي: ").append(now).append(" | ").append(timezone).append('\n')

        val spaces = state?.optJSONArray("spaces")
        if (spaces != null && spaces.length() > 0) {
            val names = mutableListOf<String>()
            for (i in 0 until minOf(spaces.length(), 20)) {
                val name = spaces.optJSONObject(i)?.optString("title")?.trim().orEmpty()
                if (name.isNotBlank()) names += name.take(80)
            }
            if (names.isNotEmpty()) {
                b.append("المساحات الموجودة: ").append(names.joinToString("، ")).append('\n')
            }
        }

        if (currentDoc != null) {
            b.append("\n[المستند الحالي]\n")
            addField(b, "الاسم", currentDoc.optString("displayName"), 160)
            addField(b, "التصنيف", currentDoc.optString("classification"), 80)
            addField(b, "الوسوم", currentDoc.optString("tags"), 260)
            addField(b, "الملخص", currentDoc.optString("summary"), 600)
            addField(b, "OCR", currentDoc.optString("ocrText"), 1800)
        }

        if (recent != null && recent.length() > 0) {
            b.append("\n[سياق المحادثة - الأقدم ثم الأحدث]\n")
            val start = (recent.length() - 8).coerceAtLeast(0)
            for (i in start until recent.length()) {
                val item = recent.optJSONObject(i) ?: continue
                val role = if (item.optString("role") == "assistant") "المساعد" else "المستخدم"
                b.append(role).append(": ")
                val displayName = item.optString("displayName").trim()
                val summary = item.optString("summary").trim()
                val messageText = item.optString("text").trim()
                val ocr = item.optString("ocrText").trim()
                if (displayName.isNotBlank()) b.append("[ملف ").append(displayName.take(120)).append("] ")
                when {
                    messageText.isNotBlank() -> b.append(messageText.take(650))
                    summary.isNotBlank() -> b.append(summary.take(650))
                    ocr.isNotBlank() -> b.append("OCR: ").append(ocr.take(700))
                }
                val tags = item.optString("tags").trim()
                if (tags.isNotBlank()) b.append(" | وسوم: ").append(tags.take(180))
                b.append('\n')
            }
        }

        b.append("\n[رسالة المستخدم الحالية]\n").append(text).append('\n')
        b.append(
            """
            
            أعد JSON فقط بهذا الشكل:
            {"reply":"رد عربي طبيعي ومحدد","classification":"note","labels":[],"keywords":[],"summary":"","confidence":0.9,"actions":[]}
            
            التصنيف: note, task, reminder, work_schedule, document, idea, personal, search, command, other.
            الأدوات المسموحة لك: search, enrich_previous_document, archive_space, pin_space, rename_space, move_last_item, create_space, rename_last_document, move_last_document.
            صيغة الأداة: {"type":"search","args":{"query":"..."},"requires_confirmation":false}
            
            افهم المرجع إلى المستند السابق مثل: هاي، هاد، فيها، الورقة، نفس الشيء.
            إذا السؤال عن محتوى المستند فأجب من OCR/الملخص الموجود ولا تخترع.
            إذا المستخدم يشرح ما هي الورقة بعد رفعها استخدم enrich_previous_document.
            إذا يسأل أين/وين/ابحث استخدم search.
            لا تقل إن شيئاً تم تنفيذه؛ التطبيق وحده ينفذ الأدوات بعد التحقق.
            إذا لا تعرف، اسأل سؤالاً واحداً محدداً بدل التخمين.
            """.trimIndent()
        )
        return b.toString().take(7800)
    }

    private fun addField(builder: StringBuilder, name: String, value: String?, limit: Int) {
        val clean = value?.trim().orEmpty()
        if (clean.isNotBlank()) {
            builder.append(name).append(": ").append(clean.take(limit)).append('\n')
        }
    }

    override fun close() {
        synchronized(lock) {
            val current = engine
            engine = null
            if (current != null && current.isInitialized()) {
                runCatching { current.close() }
            }
        }
    }

    private object FilePaths {
        fun aiCache(context: Context): String =
            java.io.File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
    }

    companion object {
        private const val SYSTEM_INSTRUCTION =
            "أنت مساعد مساحاتي الشخصي داخل تطبيق Android. افهم العربية الشامية والألمانية والإنجليزية، " +
                "واستخدم سياق المحادثة وOCR للمستندات. أعد JSON صالحاً فقط دون Markdown أو تفكير ظاهر. " +
                "لا تخترع معلومات غير موجودة ولا تدّعي تنفيذ إجراء قبل أن ينفذه التطبيق."
    }
}
