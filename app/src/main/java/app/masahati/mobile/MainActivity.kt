package app.masahati.mobile

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    private val teal = Color.rgb(54, 111, 107)
    private val paleTeal = Color.rgb(221, 233, 230)
    private val assistantBg = Color.rgb(232, 234, 231)
    private val pageBg = Color.rgb(239, 239, 234)
    private val surfaceBg = Color.rgb(247, 246, 242)
    private val controlBg = Color.rgb(230, 232, 228)
    private val worker = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private lateinit var db: MasahatiDatabase
    private lateinit var root: LinearLayout
    private var currentSpaceId: Long? = null
    private var currentSpaceTitle: String = ""
    private var showArchived = false
    private var homeSearch: String = ""
    private var chatScroll: ScrollView? = null
    private var composer: EditText? = null
    private var busyCount = 0

    private data class ActionExecution(
        val notes: String,
        val nonSearchNotes: String,
        val toolResults: JSONArray,
        val hasSearch: Boolean
    )

    private val scannerOptions by lazy {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }

    private val scanner by lazy { GmsDocumentScanning.getClient(scannerOptions) }

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data) ?: return@registerForActivityResult
        handleScan(scan)
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) handlePickedFile(uri)
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            ReminderScheduler.rescheduleAll(this)
            Toast.makeText(this, "تم تفعيل إشعارات التذكير", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "لن تظهر التنبيهات حتى تسمح بالإشعارات من إعدادات أندرويد", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = MasahatiDatabase(this)
        ReminderScheduler.ensureChannel(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                maxOf(ime, systemBars)
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentSpaceId != null) showHome() else finish()
            }
        })
        showHome()
        intent.getLongExtra("open_space_id", -1L).takeIf { it > 0L }?.let { openSpace(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getLongExtra("open_space_id", -1L)
            .takeIf { it > 0L }
            ?.let { openSpace(it) }
    }

    override fun onDestroy() {
        recognizer.close()
        worker.shutdown()
        db.close()
        super.onDestroy()
    }

    private fun showHome() {
        currentSpaceId = null
        currentSpaceTitle = ""
        root.removeAllViews()

        val top = horizontal().apply {
            setPadding(dp(18), dp(14), dp(18), dp(10))
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = text("مساحاتي", 30f, teal, true).apply { gravity = Gravity.START }
        val menu = button("⋮", 28f).apply {
            setOnClickListener { showHomeMenu(this) }
        }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(menu, LinearLayout.LayoutParams(dp(52), dp(52)))
        root.addView(top)

        val search = EditText(this).apply {
            hint = "ابحث في مساحاتك..."
            textSize = 17f
            setText(homeSearch)
            setSingleLine(true)
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(surfaceBg, 28f, Color.rgb(211, 214, 211), 1)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    homeSearch = s?.toString().orEmpty()
                    renderSpaceList()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            setMargins(dp(16), 0, dp(16), dp(10))
        })

        val listHost = LinearLayout(this).apply {
            id = SPACE_LIST_ID
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            addView(listHost, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val add = Button(this).apply {
            text = "+ مساحة جديدة"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = rounded(teal, 28f)
            isAllCaps = false
            setOnClickListener { promptNewSpace() }
        }
        root.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            setMargins(dp(22), dp(10), dp(22), dp(18))
        })
        renderSpaceList()
    }

    private fun renderSpaceList() {
        val host = root.findViewById<LinearLayout>(SPACE_LIST_ID) ?: return
        host.removeAllViews()
        val spaces = db.listSpaces(showArchived, homeSearch)
        if (spaces.isEmpty()) {
            host.addView(text(if (showArchived) "لا توجد مساحات مؤرشفة" else "لا توجد نتائج", 17f, Color.GRAY, false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(70), 0, 0)
            })
            return
        }
        spaces.forEach { space ->
            val row = horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = rounded(surfaceBg, 0f)
                setOnClickListener { openSpace(space.id) }
                setOnLongClickListener {
                    showSpaceMenu(this, space)
                    true
                }
            }
            val badge = TextView(this).apply {
                text = if (space.pinned) "★" else space.title.take(1)
                textSize = 21f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(teal, 16f)
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                addView(text(space.title, 20f, Color.rgb(30, 35, 35), true))
                val last = db.listMessages(space.id).lastOrNull()
                val preview = when {
                    last == null -> "ابدأ بالكتابة أو أضف مستنداً"
                    last.kind == "file" -> "📎 ${last.displayName ?: "ملف"}"
                    last.role == "assistant" -> "مساعد مساحاتي: ${last.text.take(60)}"
                    else -> last.text.take(65)
                }
                addView(text(preview, 14f, Color.GRAY, false).apply { maxLines = 1 })
            }
            row.addView(badge, LinearLayout.LayoutParams(dp(50), dp(50)))
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text(formatTime(space.updatedAt), 12f, Color.GRAY, false))
            host.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(10), dp(3), dp(10), dp(3))
            })
        }
    }

    private fun openSpace(id: Long) {
        val space = db.getSpace(id) ?: return
        currentSpaceId = id
        currentSpaceTitle = space.title
        showChat()
    }

    private fun showChat() {
        val spaceId = currentSpaceId ?: return
        root.removeAllViews()

        val top = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(surfaceBg)
        }
        val back = button("←", 26f).apply { setOnClickListener { showHome() } }
        val title = text(currentSpaceTitle, 29f, Color.rgb(25, 30, 30), true).apply { gravity = Gravity.CENTER }
        val menu = button("⋮", 28f).apply { setOnClickListener { showChatMenu(this) } }
        top.addView(back, LinearLayout.LayoutParams(dp(56), dp(56)))
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(menu, LinearLayout.LayoutParams(dp(56), dp(56)))
        root.addView(top)

        val messagesHost = LinearLayout(this).apply {
            id = MESSAGE_LIST_ID
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(16))
        }
        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(messagesHost, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(surfaceBg)
        }
        val plus = button("+", 34f).apply {
            background = rounded(controlBg, 4f)
            setOnClickListener { showAttachMenu(this) }
        }
        composer = EditText(this).apply {
            hint = "اكتب لنفسك..."
            textSize = 18f
            maxLines = 5
            minLines = 1
            setPadding(dp(17), dp(9), dp(17), dp(9))
            background = rounded(Color.rgb(244, 244, 240), 28f)
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) chatScroll?.postDelayed({ chatScroll?.fullScroll(View.FOCUS_DOWN) }, 180L)
            }
            setOnClickListener {
                chatScroll?.postDelayed({ chatScroll?.fullScroll(View.FOCUS_DOWN) }, 120L)
            }
        }
        val send = Button(this).apply {
            text = "إرسال"
            textSize = 17f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = rounded(teal, 26f)
            setOnClickListener { sendText() }
        }
        bottom.addView(plus, LinearLayout.LayoutParams(dp(62), dp(58)))
        bottom.addView(composer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(10), 0, dp(10), 0)
        })
        bottom.addView(send, LinearLayout.LayoutParams(dp(94), dp(58)))
        root.addView(bottom)
        renderMessages(spaceId)
    }

    private fun renderMessages(spaceId: Long) {
        val host = root.findViewById<LinearLayout>(MESSAGE_LIST_ID) ?: return
        host.removeAllViews()
        val messages = db.listMessages(spaceId)
        if (messages.isEmpty()) {
            host.addView(text("اكتب ملاحظة أو امسح مستنداً. مساعد مساحاتي سيفهمه ويصنفه تلقائياً.", 16f, Color.GRAY, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(70), dp(28), 0)
            })
        } else {
            messages.forEach { host.addView(messageBubble(it)) }
        }
        if (busyCount > 0) {
            val waiting = MessageRow(-1, spaceId, "assistant", "text", "جاري الفهم والترتيب…", null, null, null, null, null, null, null, false, System.currentTimeMillis())
            host.addView(messageBubble(waiting, temporary = true))
        }
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun messageBubble(m: MessageRow, temporary: Boolean = false): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (m.role == "assistant") Gravity.START else Gravity.END
            setPadding(0, dp(5), 0, dp(5))
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(9))
            background = rounded(
                if (m.role == "assistant") assistantBg else paleTeal,
                25f,
                if (m.role == "assistant") Color.rgb(210, 214, 211) else Color.TRANSPARENT,
                if (m.role == "assistant") 1 else 0
            )
            alpha = if (temporary) 0.8f else 1f
        }
        if (m.role == "assistant") {
            bubble.addView(text("مساعد مساحاتي", 12f, teal, true))
        }
        if (m.kind == "file") {
            val name = m.displayName ?: "ملف"
            bubble.addView(text("📎 $name", 17f, Color.rgb(35, 40, 40), true))
            bubble.addView(text(if (m.ocrText.isNullOrBlank()) "اضغط لفتح الملف" else "تمت قراءته وتصنيفه للبحث", 14f, Color.DKGRAY, false))
            if (!temporary && m.filePath != null) {
                bubble.setOnClickListener { openSavedFile(m) }
            }
        } else {
            bubble.addView(text(m.text, 18f, Color.rgb(30, 35, 35), false))
        }
        if (!temporary && m.id > 0) {
            bubble.setOnLongClickListener {
                showMessageMenu(bubble, m)
                true
            }
        }
        if (m.id > 0 && !temporary && m.role != "assistant") {
            val meta = listOfNotNull(m.classification, m.tags).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) bubble.addView(text(meta.take(120), 12f, teal, false))
        }
        if (!temporary && m.starred) {
            bubble.addView(text("★ مميز", 12f, teal, true))
        }
        if (!temporary) bubble.addView(text(formatTime(m.createdAt), 11f, Color.GRAY, false))
        val bubbleWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        wrap.addView(bubble, LinearLayout.LayoutParams(bubbleWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    private fun sendText() {
        val spaceId = currentSpaceId ?: return
        val value = composer?.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) return
        composer?.setText("")
        val id = db.insertText(spaceId, "user", value)
        renderMessages(spaceId)
        analyzeWithAgent(id, value, currentSpaceTitle)
    }

    private fun analyzeWithAgent(messageId: Long, content: String, spaceTitle: String) {
        val spaceId = currentSpaceId ?: return
        busyCount++
        renderMessages(spaceId)
        worker.execute {
            val recent = db.recentForAi(spaceId, 20).filter { it.id != messageId }
            try {
                val body = JSONObject().apply {
                    put("text", content.take(6000))
                    put("spaceTitle", spaceTitle)
                    put("now", ZonedDateTime.now().toString())
                    put("timezone", java.time.ZoneId.systemDefault().id)

                    val appState = JSONObject().apply {
                        put("currentSpaceId", spaceId)
                        put("currentSpaceTitle", spaceTitle)
                        val spaces = JSONArray()
                        (db.listSpaces(false).take(24) + db.listSpaces(true).take(8))
                            .distinctBy { it.id }
                            .forEach { space ->
                                spaces.put(JSONObject().apply {
                                    put("id", space.id)
                                    put("title", space.title)
                                    put("pinned", space.pinned)
                                    put("archived", space.archived)
                                })
                            }
                        put("spaces", spaces)
                        db.lastFileMessage(spaceId)?.let { doc ->
                            put("currentDocument", JSONObject().apply {
                                put("id", doc.id)
                                put("displayName", doc.displayName ?: "")
                                put("classification", doc.classification ?: "")
                                put("tags", doc.tags ?: "")
                                put("summary", doc.summary ?: "")
                                put("ocrText", doc.ocrText.orEmpty().take(3200))
                                put("createdAt", doc.createdAt)
                            })
                        }
                    }
                    put("appState", appState)

                    var remaining = 16000
                    val contextRows = mutableListOf<JSONObject>()
                    fun takeBudget(value: String?, cap: Int): String {
                        if (remaining <= 0 || value.isNullOrBlank()) return ""
                        val clean = value.trim()
                        val count = minOf(cap, remaining, clean.length)
                        remaining -= count
                        return clean.take(count)
                    }

                    recent.asReversed().forEach { msg ->
                        if (remaining <= 160) return@forEach
                        val item = JSONObject().apply {
                            put("role", if (msg.role == "assistant") "assistant" else "user")
                            put("kind", msg.kind)
                            put("displayName", takeBudget(msg.displayName, 180))
                            put("classification", takeBudget(msg.classification, 80))
                            put("tags", takeBudget(msg.tags, 320))
                            put("summary", takeBudget(msg.summary, 700))
                            put("text", takeBudget(msg.text, 1300))
                            put("ocrText", takeBudget(msg.ocrText, 3400))
                            put("createdAt", msg.createdAt)
                        }
                        contextRows.add(0, item)
                    }
                    val arr = JSONArray()
                    contextRows.forEach { arr.put(it) }
                    put("recent", arr)
                }

                val directReminderText = content.takeIf { NaturalReminderParser.looksLikeReminder(it) }
                val reminderFollowUpText = if (directReminderText == null && content.length <= 40) {
                    val lastAssistant = recent.lastOrNull { it.role == "assistant" }
                    val assistantAskedForReminderDetail = lastAssistant?.text?.let { answer ->
                        listOf("صباح", "مساء", "أي ساعة", "هل تقصد", "موعد", "وقت التذكير")
                            .any { token -> answer.contains(token, ignoreCase = true) }
                    } == true
                    if (assistantAskedForReminderDetail) {
                        recent.asReversed()
                            .firstOrNull { it.role == "user" && NaturalReminderParser.looksLikeReminder(it.text) }
                            ?.text
                            ?.let { previous -> previous + " " + content }
                    } else null
                } else null
                val reminderSourceText = directReminderText ?: reminderFollowUpText
                val reminderResolution = reminderSourceText?.let { reminderText ->
                    NaturalReminderParser.parseWithContext(
                        reminderText,
                        recent.filter { it.role == "user" }.map { it.text }.filter { it.isNotBlank() },
                        ZonedDateTime.now()
                    )
                }
                val resolvedReminderText = reminderSourceText ?: content
                val localReminderResult = reminderResolution?.let { resolution ->
                    val actionArgs = JSONObject()
                        .put("title", "تذكير مساحاتي")
                        .put("body", resolvedReminderText.take(500))
                    resolution.delayMinutes?.let { actionArgs.put("delay_minutes", it) }
                    if (resolution.repeatRule == "daily") {
                        actionArgs.put("repeat", "daily")
                        if (resolution.hour != null && resolution.minute != null) {
                            actionArgs.put("time", String.format(Locale.ROOT, "%02d:%02d", resolution.hour, resolution.minute))
                        }
                    } else if (resolution.repeatRule == "weekly") {
                        actionArgs.put("repeat", "weekly")
                        resolution.dayOfWeek?.let { actionArgs.put("day_of_week", it) }
                        if (resolution.hour != null && resolution.minute != null) {
                            actionArgs.put("time", String.format(Locale.ROOT, "%02d:%02d", resolution.hour, resolution.minute))
                        }
                    } else {
                        resolution.triggerAt?.let { epoch ->
                            val iso = ZonedDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(epoch),
                                java.time.ZoneId.systemDefault()
                            ).toString()
                            actionArgs.put("trigger_at", iso)
                        }
                    }

                    JSONObject()
                        .put("ok", true)
                        .put("engine", "local-reminder-parser")
                        .put("classification", "reminder")
                        .put("labels", JSONArray(listOf("تذكير")))
                        .put("keywords", JSONArray())
                        .put("summary", resolvedReminderText.take(320))
                        .put("confidence", if (resolution.ready) 0.99 else 0.95)
                        .put(
                            "reply",
                            if (resolution.ready) "فهمت موعد التذكير."
                            else resolution.clarification ?: "أحتاج موعداً أوضح لإنشاء التذكير."
                        )
                        .put(
                            "actions",
                            if (resolution.ready) {
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "create_reminder")
                                        .put("args", actionArgs)
                                        .put("requires_confirmation", false)
                                )
                            } else JSONArray()
                        )
                }

                // Explicit local tool commands are deterministic and should not depend on model latency
                // or on the model remembering to mark a directly requested action as executable.
                val localToolCandidate = if (localReminderResult == null) {
                    LocalAssistantFallback.analyze(content, spaceTitle, recent)
                } else null
                val hasLocalToolAction = localToolCandidate
                    ?.optJSONArray("actions")
                    ?.let { it.length() > 0 } == true
                val remote = if (localReminderResult == null && !hasLocalToolAction) {
                    runCatching { postAgent(body) }.getOrNull()
                } else null
                val result = localReminderResult
                    ?: localToolCandidate?.takeIf { hasLocalToolAction }
                    ?: if (remote?.optBoolean("ok", false) == true) remote
                    else localToolCandidate ?: LocalAssistantFallback.analyze(content, spaceTitle, recent)

                if (reminderResolution != null) {
                    val sourceActions = result.optJSONArray("actions") ?: JSONArray()
                    val safeActions = JSONArray()
                    var hasReminderAction = false
                    for (i in 0 until sourceActions.length()) {
                        val item = sourceActions.optJSONObject(i) ?: continue
                        if (item.optString("type") == "create_reminder") {
                            hasReminderAction = true
                            if (reminderResolution.ready) safeActions.put(item)
                        } else {
                            safeActions.put(item)
                        }
                    }
                    if (reminderResolution.ready && !hasReminderAction) {
                        safeActions.put(
                            JSONObject()
                                .put("type", "create_reminder")
                                .put(
                                    "args",
                                    JSONObject()
                                        .put("title", "تذكير مساحاتي")
                                        .put("body", resolvedReminderText.take(500))
                                )
                                .put("requires_confirmation", false)
                        )
                    }
                    result.put("actions", safeActions)
                    result.put("classification", "reminder")
                    if (!reminderResolution.ready) {
                        result.put("reply", reminderResolution.clarification ?: "أحتاج موعداً أوضح لإنشاء التذكير.")
                    }
                }

                val labels = result.optJSONArray("labels")?.toStringList()?.joinToString("، ").orEmpty()
                val classification = result.optString("classification", "other")
                val summary = result.optString("summary", "")
                db.updateAi(messageId, classification, labels, summary, result.toString())
                maybeApplySmartDocumentName(spaceId, messageId, classification, result.optJSONArray("labels"))
                val execution = executeAgentActions(spaceId, messageId, result.optJSONArray("actions"), content)
                var reply = result.optString("reply", "فهمت المحتوى وحفظته.")
                var actionText = execution.notes

                // Search is a real local tool: answer from the ranked tool result immediately.
                // This avoids a second network round-trip and never uploads full OCR search hits.
                if (execution.hasSearch && execution.toolResults.length() > 0) {
                    val toolReply = buildToolAwareReply(content, execution.toolResults)
                    if (toolReply.isNotBlank()) {
                        reply = toolReply
                        actionText = execution.nonSearchNotes
                    }
                }
                db.insertText(spaceId, "assistant", listOf(reply, actionText).filter { it.isNotBlank() }.joinToString("\n\n"))
            } catch (_: Exception) {
                val fallback = LocalAssistantFallback.analyze(content, spaceTitle, recent)
                db.updateAi(
                    messageId,
                    fallback.optString("classification", "note"),
                    fallback.optJSONArray("labels")?.toStringList()?.joinToString("، ").orEmpty(),
                    fallback.optString("summary", content.take(220)),
                    fallback.toString()
                )
                db.insertText(spaceId, "assistant", fallback.optString("reply", "حفظت المحتوى محلياً، لكن التحليل السحابي لم يكتمل."))
            } finally {
                busyCount = (busyCount - 1).coerceAtLeast(0)
                runOnUiThread {
                    if (currentSpaceId == spaceId) {
                        currentSpaceTitle = db.getSpace(spaceId)?.title ?: currentSpaceTitle
                        showChat()
                    }
                }
            }
        }
    }

    private fun executeAgentActions(spaceId: Long, sourceMessageId: Long, actions: JSONArray?, sourceText: String): ActionExecution {
        if (actions == null) return ActionExecution("", "", JSONArray(), false)
        val notes = mutableListOf<String>()
        val nonSearchNotes = mutableListOf<String>()
        val toolResults = JSONArray()
        var hasSearch = false

        fun recordNote(note: String, searchNote: Boolean = false) {
            if (note.isBlank()) return
            notes += note
            if (!searchNote) nonSearchNotes += note
        }

        fun recordTool(type: String, status: String, payload: JSONObject = JSONObject()) {
            toolResults.put(
                JSONObject()
                    .put("type", type)
                    .put("status", status)
                    .put("result", payload)
            )
        }

        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            val type = action.optString("type")
            val args = action.optJSONObject("args") ?: JSONObject()
            val needsConfirm = action.optBoolean("requires_confirmation", true)
            when (type) {
                "create_reminder" -> {
                    val created = ReminderScheduler.createFromAgent(this, db, spaceId, args, sourceText)
                    if (created == null) {
                        val note = "فهمت أنك تريد تنبيهاً، لكن الموعد غير واضح بما يكفي لإنشائه."
                        recordNote(note)
                        recordTool(type, "failed", JSONObject().put("reason", "insufficient_time_details"))
                    } else {
                        val precision = if (created.exact) "" else " قد يتأخر بضع دقائق ما لم تفعّل دقة التنبيهات من إعدادات أندرويد."
                        val permissionNote = if (ReminderScheduler.notificationsAllowed(this)) "" else " سأطلب منك الآن السماح بإشعارات التطبيق."
                        val note = "تم إنشاء تنبيه فعلي: ${created.description}.$precision$permissionNote"
                        recordNote(note)
                        recordTool(type, "completed", JSONObject().put("description", created.description).put("exact", created.exact))
                        runOnUiThread { maybeRequestNotificationPermission() }
                    }
                }
                "enrich_previous_document" -> {
                    val target = db.lastFileMessage(spaceId)
                    if (target != null) {
                        val newSummary = args.optString("summary").trim().ifBlank { target.summary.orEmpty() }
                        val labelList = args.optJSONArray("labels")?.toStringList().orEmpty()
                        val keywordList = args.optJSONArray("keywords")?.toStringList().orEmpty()
                        val mergedTags = (labelList + keywordList + target.tags.orEmpty().split('،').map { it.trim() })
                            .filter { it.isNotBlank() }.distinct().take(12).joinToString("، ")
                        db.updateAi(
                            target.id,
                            "document",
                            mergedTags,
                            newSummary,
                            JSONObject().put("source", "user_clarification").put("summary", newSummary).put("tags", mergedTags).toString()
                        )
                        val note = "ربطت هذه المعلومة بالمستند السابق وحدّثت وصفه وكلمات البحث."
                        recordNote(note)
                        recordTool(type, "completed", JSONObject().put("document_id", target.id).put("summary", newSummary).put("tags", mergedTags))
                    } else recordTool(type, "failed", JSONObject().put("reason", "no_previous_document"))
                }
                "search" -> {
                    hasSearch = true
                    val q = args.optString("query").trim()
                    if (q.isNotBlank()) {
                        // Do not let the search command itself rank as its own best result.
                        val found = db.search(q, 16)
                            .filter { it.id != sourceMessageId && it.role == "user" }
                            .take(8)
                        val resultArray = JSONArray()
                        found.take(5).forEach { m ->
                            val s = db.getSpace(m.spaceId)
                            resultArray.put(
                                JSONObject()
                                    .put("spaceId", m.spaceId)
                                    .put("spaceTitle", s?.title.orEmpty())
                                    .put("messageId", m.id)
                                    .put("kind", m.kind)
                                    .put("displayName", m.displayName.orEmpty())
                                    .put("classification", m.classification.orEmpty())
                                    .put("tags", m.tags.orEmpty().take(260))
                                    .put("summary", m.summary.orEmpty().take(420))
                                    .put("text", if (m.kind == "text") m.text.take(320) else "")
                                    .put("createdAt", m.createdAt)
                            )
                        }
                        if (found.isEmpty()) {
                            recordNote("لم أجد نتيجة محلية مطابقة لـ «$q».", searchNote = true)
                        } else {
                            val lines = found.take(5).mapNotNull { m ->
                                val s = db.getSpace(m.spaceId)?.title ?: return@mapNotNull null
                                val preview = when {
                                    !m.displayName.isNullOrBlank() -> m.displayName
                                    m.summary?.isNotBlank() == true -> m.summary
                                    else -> m.text.take(85)
                                }
                                "• $s — $preview"
                            }
                            recordNote("وجدت محلياً:\n${lines.joinToString("\n")}", searchNote = true)
                        }
                        recordTool(type, "completed", JSONObject().put("query", q).put("results", resultArray))
                    } else recordTool(type, "failed", JSONObject().put("reason", "empty_query"))
                }
                "archive_space" -> {
                    val target = args.optString("space_name").ifBlank { db.getSpace(spaceId)?.title.orEmpty() }
                    val space = db.findSpaceByTitle(target) ?: db.getSpace(spaceId)
                    if (space != null) {
                        if (needsConfirm) {
                            recordNote("الأرشفة جاهزة، لكني لم أنفذها لأن الإجراء يحتاج تأكيداً.")
                            recordTool(type, "waiting_confirmation", JSONObject().put("space", space.title))
                        } else {
                            db.setArchived(space.id, true)
                            recordNote("تمت أرشفة مساحة «${space.title}».")
                            recordTool(type, "completed", JSONObject().put("space", space.title))
                        }
                    }
                }
                "pin_space" -> {
                    val target = args.optString("space_name").ifBlank { db.getSpace(spaceId)?.title.orEmpty() }
                    val space = db.findSpaceByTitle(target) ?: db.getSpace(spaceId)
                    if (space != null) {
                        if (needsConfirm) {
                            recordNote("التثبيت جاهز وينتظر التأكيد.")
                            recordTool(type, "waiting_confirmation", JSONObject().put("space", space.title))
                        } else {
                            db.setPinned(space.id, true)
                            recordNote("تم تثبيت مساحة «${space.title}».")
                            recordTool(type, "completed", JSONObject().put("space", space.title))
                        }
                    }
                }
                "rename_space" -> {
                    val newName = args.optString("new_name").ifBlank { args.optString("title") }.trim()
                    if (newName.isNotBlank()) {
                        if (needsConfirm) {
                            recordNote("إعادة التسمية جاهزة وينتظر التأكيد.")
                            recordTool(type, "waiting_confirmation", JSONObject().put("new_name", newName))
                        } else {
                            db.renameSpace(spaceId, newName)
                            recordNote("تم تغيير اسم المساحة إلى «$newName».")
                            recordTool(type, "completed", JSONObject().put("new_name", newName))
                        }
                    }
                }
                "move_last_item" -> {
                    val targetName = args.optString("target_space").ifBlank { args.optString("space_name") }.trim()
                    val target = db.findSpaceByTitle(targetName)
                    // The current command is already stored as a user message; move the item before it.
                    val last = db.listMessages(spaceId).asReversed()
                        .firstOrNull { it.id != sourceMessageId && it.role == "user" }
                    if (target != null && last != null) {
                        if (needsConfirm) {
                            recordNote("النقل جاهز وينتظر التأكيد.")
                            recordTool(type, "waiting_confirmation", JSONObject().put("target_space", target.title))
                        } else {
                            db.moveMessage(last.id, target.id)
                            recordNote("تم نقل آخر عنصر إلى «${target.title}».")
                            recordTool(type, "completed", JSONObject().put("target_space", target.title).put("message_id", last.id))
                        }
                    } else recordTool(type, "failed", JSONObject().put("reason", "target_or_item_missing"))
                }
                "create_space" -> {
                    val name = args.optString("name").ifBlank { args.optString("title") }.trim()
                    if (name.isNotBlank()) {
                        val existing = db.findSpaceByTitle(name)
                        if (existing != null) {
                            recordNote("مساحة «${existing.title}» موجودة أصلاً.")
                            recordTool(type, "completed", JSONObject().put("space_id", existing.id).put("space", existing.title).put("already_existed", true))
                        } else if (needsConfirm) {
                            recordNote("إنشاء مساحة «$name» جاهز وينتظر التأكيد.")
                            recordTool(type, "waiting_confirmation", JSONObject().put("space", name))
                        } else {
                            val id = db.createSpace(name)
                            recordNote("تم إنشاء مساحة «$name».")
                            recordTool(type, "completed", JSONObject().put("space_id", id).put("space", name))
                        }
                    }
                }
                "rename_last_document" -> {
                    val target = db.lastFileMessage(spaceId)
                    val newName = args.optString("new_name").ifBlank { args.optString("name") }.trim()
                    if (target != null && newName.isNotBlank()) {
                        if (needsConfirm) {
                            recordNote("تغيير اسم المستند إلى «$newName» جاهز وينتظر التأكيد.")
                            recordTool(type, "waiting_confirmation", JSONObject().put("document_id", target.id).put("new_name", newName))
                        } else {
                            db.renameMessageDisplayName(target.id, newName)
                            recordNote("تم تغيير اسم المستند إلى «$newName».")
                            recordTool(type, "completed", JSONObject().put("document_id", target.id).put("new_name", newName))
                        }
                    } else recordTool(type, "failed", JSONObject().put("reason", "document_or_name_missing"))
                }
                "move_last_document" -> {
                    val targetName = args.optString("target_space").ifBlank { args.optString("space_name") }.trim()
                    val targetSpace = db.findSpaceByTitle(targetName)
                    val document = db.lastFileMessage(spaceId)
                    if (targetSpace != null && document != null) {
                        if (needsConfirm) {
                            recordNote("نقل المستند إلى «${targetSpace.title}» جاهز وينتظر التأكيد.")
                            recordTool(type, "waiting_confirmation", JSONObject().put("target_space", targetSpace.title).put("document_id", document.id))
                        } else {
                            db.moveMessage(document.id, targetSpace.id)
                            recordNote("تم نقل المستند إلى «${targetSpace.title}».")
                            recordTool(type, "completed", JSONObject().put("target_space", targetSpace.title).put("document_id", document.id))
                        }
                    } else recordTool(type, "failed", JSONObject().put("reason", "target_or_document_missing"))
                }
            }
        }
        return ActionExecution(
            notes = notes.joinToString("\n"),
            nonSearchNotes = nonSearchNotes.joinToString("\n"),
            toolResults = toolResults,
            hasSearch = hasSearch
        )
    }

    private fun maybeApplySmartDocumentName(spaceId: Long, messageId: Long, classification: String, labels: JSONArray?) {
        val file = db.lastFileMessage(spaceId) ?: return
        if (file.id != messageId) return
        val currentName = file.displayName.orEmpty()
        if (!currentName.startsWith("Scan-", ignoreCase = true) || !currentName.endsWith(".pdf", ignoreCase = true)) return

        val rawLabels = labels?.toStringList().orEmpty()
            .map { it.trim().replace(Regex("[\\/:*?\"<>|]+"), " ") }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 2..45 }
            .filterNot {
                it.lowercase() in setOf(
                    "مستند", "وثيقة", "document", "pdf", "ملف", "ملاحظة", "note", "بحث", "search",
                    "تذكير", "reminder", "other"
                )
            }
            .distinctBy { it.lowercase() }
            .take(3)

        val titleParts = when {
            rawLabels.isNotEmpty() -> rawLabels
            classification == "work_schedule" -> listOf("جدول دوام")
            else -> emptyList()
        }
        if (titleParts.isEmpty()) return

        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(file.createdAt))
        val smartName = (titleParts.joinToString(" - ") + " - " + date + ".pdf").take(170)
        if (smartName != currentName) db.renameMessageDisplayName(messageId, smartName)
    }

    private fun buildToolAwareReply(sourceText: String, toolResults: JSONArray): String {
        for (i in 0 until toolResults.length()) {
            val tool = toolResults.optJSONObject(i) ?: continue
            if (tool.optString("type") != "search") continue
            val result = tool.optJSONObject("result") ?: continue
            val query = result.optString("query").ifBlank { sourceText.trim() }
            val matches = result.optJSONArray("results") ?: JSONArray()
            if (matches.length() == 0) {
                return "ما لقيت نتيجة محفوظة تطابق «$query». جرّب كلمة من اسم الورقة أو الجهة الموجودة عليها."
            }
            val first = matches.optJSONObject(0) ?: continue
            val space = first.optString("spaceTitle").ifBlank { "مساحة غير مسماة" }
            val displayName = first.optString("displayName").trim()
            val summary = first.optString("summary").trim()
            val note = first.optString("text").trim()
            val description = when {
                displayName.isNotBlank() && summary.isNotBlank() -> "الملف «$displayName» — ${summary.take(220)}"
                displayName.isNotBlank() -> "الملف «$displayName»"
                summary.isNotBlank() -> summary.take(240)
                note.isNotBlank() -> note.take(240)
                else -> "عنصر محفوظ"
            }
            val extra = if (matches.length() > 1) " وفي ${matches.length() - 1} نتيجة قريبة كمان." else ""
            return "أقرب نتيجة لطلبك موجودة في مساحة «$space»: $description.$extra"
        }
        return ""
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun postAgent(body: JSONObject): JSONObject {
        val conn = (URL(AGENT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return if (raw.isBlank()) JSONObject().put("ok", false) else JSONObject(raw)
    }

    private fun startSmartScanner() {
        setBusyToast("جاري تجهيز السكانر الذكي…")
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> scannerLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e ->
                Toast.makeText(this, "تعذر تشغيل السكانر: ${e.localizedMessage ?: "غير متاح"}", Toast.LENGTH_LONG).show()
            }
    }

    private fun handleScan(scan: GmsDocumentScanningResult) {
        val spaceId = currentSpaceId ?: return
        val pdf = scan.pdf
        val pages = scan.pages.orEmpty()
        if (pdf == null && pages.isEmpty()) return
        busyCount++
        renderMessages(spaceId)
        worker.execute {
            try {
                val now = System.currentTimeMillis()
                val fileName = "Scan-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))}.pdf"
                val target = File(filesDir, "documents").apply { mkdirs() }.resolve(fileName)
                val ocrBuilder = StringBuilder()
                val cleanedPageCount = if (pages.isNotEmpty()) {
                    DocumentImageEnhancer.processPagesToPdf(
                        this@MainActivity,
                        pages.map { it.imageUri },
                        target
                    ) { index, cleanedBitmap ->
                        try {
                            val image = InputImage.fromBitmap(cleanedBitmap, 0)
                            val recognized = Tasks.await(recognizer.process(image)).text.trim()
                            if (recognized.isNotBlank()) {
                                if (ocrBuilder.isNotEmpty()) ocrBuilder.append("\n\n")
                                ocrBuilder.append("صفحة ${index + 1}:\n")
                                ocrBuilder.append(recognized)
                            }
                        } catch (_: Exception) { }
                    }
                } else {
                    if (pdf == null) throw IllegalStateException("PDF result missing")
                    contentResolver.openInputStream(pdf.uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: throw IllegalStateException("Cannot read scanned PDF")
                    pdf.pageCount
                }
                val ocr = ocrBuilder.toString()
                val messageId = db.insertFile(spaceId, "user", fileName, target.absolutePath, "application/pdf", ocr)
                val aiText = if (ocr.isBlank()) {
                    "مستند ممسوح ضوئياً باسم $fileName وعدد صفحاته $cleanedPageCount. صنفه ونظم كلمات البحث المناسبة بدون اختراع محتوى غير ظاهر."
                } else {
                    "مستند ممسوح ضوئياً باسم $fileName بعد تنظيف الظلال تلقائياً. النص المستخرج محلياً:\n${ocr.take(4800)}"
                }
                runOnUiThread {
                    busyCount = (busyCount - 1).coerceAtLeast(0)
                    if (currentSpaceId == spaceId) renderMessages(spaceId)
                    confirmCloudDocumentAnalysis(messageId, aiText, currentSpaceTitle)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busyCount = (busyCount - 1).coerceAtLeast(0)
                    renderMessages(spaceId)
                    Toast.makeText(this, "لم يكتمل حفظ المسح: ${e.localizedMessage ?: "خطأ"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handlePickedFile(uri: Uri) {
        val spaceId = currentSpaceId ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        busyCount++
        renderMessages(spaceId)
        worker.execute {
            try {
                val displayName = queryDisplayName(uri) ?: "ملف-${System.currentTimeMillis()}"
                val mime = contentResolver.getType(uri) ?: guessMime(displayName)
                val target = File(filesDir, "documents").apply { mkdirs() }.resolve(safeFileName(displayName))
                contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: throw IllegalStateException("Cannot read file")
                var ocr = ""
                if (mime.startsWith("image/")) {
                    try {
                        val image = InputImage.fromFilePath(this@MainActivity, uri)
                        ocr = Tasks.await(recognizer.process(image)).text.trim()
                    } catch (_: Exception) { }
                } else if (mime == "application/pdf" || displayName.endsWith(".pdf", ignoreCase = true)) {
                    ocr = extractPdfOcr(target)
                }
                val id = db.insertFile(spaceId, "user", displayName, target.absolutePath, mime, ocr)
                val aiText = when {
                    ocr.isNotBlank() -> "ملف مرفق باسم $displayName. النص المستخرج محلياً:\n${ocr.take(4800)}"
                    else -> "تم إرفاق ملف باسم $displayName ونوعه $mime. صنفه بالاعتماد فقط على الاسم والنوع ولا تخترع محتوى داخله."
                }
                runOnUiThread {
                    busyCount = (busyCount - 1).coerceAtLeast(0)
                    renderMessages(spaceId)
                    confirmCloudDocumentAnalysis(id, aiText, currentSpaceTitle)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busyCount = (busyCount - 1).coerceAtLeast(0)
                    renderMessages(spaceId)
                    Toast.makeText(this, "تعذر حفظ الملف: ${e.localizedMessage ?: "خطأ"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun extractPdfOcr(file: File): String {
        val out = StringBuilder()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pageLimit = minOf(renderer.pageCount, 20)
                    for (index in 0 until pageLimit) {
                        if (out.length >= 24000) break
                        renderer.openPage(index).use { page ->
                            val maxSide = 1500f
                            // Keep one rendered page bounded in memory, even for unusually large PDF page dimensions.
                            val scale = (maxSide / maxOf(page.width, page.height).toFloat()).coerceAtMost(2.0f)
                            val width = (page.width * scale).toInt().coerceIn(1, maxSide.toInt())
                            val height = (page.height * scale).toInt().coerceIn(1, maxSide.toInt())
                            val bitmap = createBitmap(width, height)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val recognized = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text.trim()
                                if (recognized.isNotBlank()) {
                                    if (out.isNotEmpty()) out.append("\n\n")
                                    out.append("صفحة ").append(index + 1).append(":\n")
                                    out.append(recognized)
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            return out.toString()
        }
        return out.toString()
    }

    private fun confirmCloudDocumentAnalysis(messageId: Long, aiText: String, spaceTitle: String) {
        val spaceId = currentSpaceId ?: return
        AlertDialog.Builder(this)
            .setTitle("تحليل المستند بالذكاء؟")
            .setMessage("تم حفظ المستند محلياً. للتحليل والتصنيف سأرسل النص المستخرج أو بيانات الملف فقط إلى خدمة الذكاء، وليس صورة المستند أو ملف PDF نفسه.")
            .setPositiveButton("تحليل ذكي") { _, _ -> analyzeWithAgent(messageId, aiText, spaceTitle) }
            .setNegativeButton("محلي فقط") { _, _ ->
                db.insertText(spaceId, "assistant", "تم حفظ المستند محلياً بدون إرساله للتحليل السحابي.")
                renderMessages(spaceId)
            }
            .show()
    }

    private fun openSavedFile(m: MessageRow) {
        val path = m.filePath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "الملف غير موجود", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, m.mimeType ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "لا يوجد تطبيق مناسب لفتح هذا الملف", Toast.LENGTH_LONG).show()
        }
    }

    private fun showAttachMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("سكانر ذكي")
            menu.add("إرفاق ملف أو صورة")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "سكانر ذكي" -> startSmartScanner()
                    else -> filePicker.launch(arrayOf("application/pdf", "image/*", "text/*"))
                }
                true
            }
            show()
        }
    }

    private fun showChatMenu(anchor: View) {
        val space = currentSpaceId?.let { db.getSpace(it) } ?: return
        PopupMenu(this, anchor).apply {
            menu.add(if (space.pinned) "إلغاء التثبيت" else "تثبيت")
            menu.add("إعادة تسمية")
            menu.add("بحث شامل")
            menu.add("المميزة ★")
            menu.add("أرشفة")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "تثبيت" -> { db.setPinned(space.id, true); Toast.makeText(this@MainActivity, "تم التثبيت", Toast.LENGTH_SHORT).show() }
                    "إلغاء التثبيت" -> { db.setPinned(space.id, false); Toast.makeText(this@MainActivity, "تم إلغاء التثبيت", Toast.LENGTH_SHORT).show() }
                    "إعادة تسمية" -> promptRename(space)
                    "بحث شامل" -> promptGlobalSearch()
                    "المميزة ★" -> showStarredMessages(space.id)
                    "أرشفة" -> confirmArchive(space)
                }
                true
            }
            show()
        }
    }

    private fun showHomeMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(if (showArchived) "المساحات النشطة" else "المؤرشفة")
            menu.add("بحث ذكي شامل")
            menu.add("إعداد دقة التنبيهات")
            setOnMenuItemClickListener {
                when {
                    it.title.toString().contains("بحث") -> promptGlobalSearch()
                    it.title.toString().contains("دقة التنبيهات") -> ReminderScheduler.openExactAlarmSettings(this@MainActivity)
                    else -> { showArchived = !showArchived; showHome() }
                }
                true
            }
            show()
        }
    }

    private fun showSpaceMenu(anchor: View, space: SpaceRow) {
        PopupMenu(this, anchor).apply {
            menu.add(if (space.pinned) "إلغاء التثبيت" else "تثبيت")
            menu.add(if (space.archived) "استعادة" else "أرشفة")
            menu.add("إعادة تسمية")
            menu.add("حذف")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "تثبيت" -> db.setPinned(space.id, true)
                    "إلغاء التثبيت" -> db.setPinned(space.id, false)
                    "أرشفة" -> db.setArchived(space.id, true)
                    "استعادة" -> db.setArchived(space.id, false)
                    "إعادة تسمية" -> promptRename(space)
                    "حذف" -> confirmDeleteSpace(space)
                }
                renderSpaceList()
                true
            }
            show()
        }
    }

    private fun promptGlobalSearch() {
        val input = EditText(this).apply { hint = "مثال: جواز السفر أو جدول دوامي"; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        AlertDialog.Builder(this)
            .setTitle("بحث شامل")
            .setView(input)
            .setPositiveButton("بحث") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isBlank()) return@setPositiveButton
                val results = db.search(q, 20)
                val text = if (results.isEmpty()) "لم أجد نتائج لـ «$q»." else results.joinToString("\n\n") { m ->
                    val s = db.getSpace(m.spaceId)?.title ?: "مساحة"
                    val p = m.displayName ?: m.summary ?: m.text.take(100)
                    "• $s\n$p"
                }
                AlertDialog.Builder(this).setTitle("نتائج البحث").setMessage(text).setPositiveButton("حسناً", null).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun promptNewSpace() {
        val input = EditText(this).apply { hint = "اسم المساحة" }
        AlertDialog.Builder(this).setTitle("مساحة جديدة").setView(input)
            .setPositiveButton("إنشاء") { _, _ ->
                val id = db.createSpace(input.text.toString())
                openSpace(id)
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun promptRename(space: SpaceRow) {
        val input = EditText(this).apply { setText(space.title); selectAll() }
        AlertDialog.Builder(this).setTitle("إعادة تسمية").setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    db.renameSpace(space.id, value)
                    if (currentSpaceId == space.id) { currentSpaceTitle = value; showChat() } else renderSpaceList()
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun confirmArchive(space: SpaceRow) {
        AlertDialog.Builder(this).setTitle("أرشفة المساحة؟")
            .setMessage("ستبقى محفوظة ويمكن استعادتها من المؤرشفة.")
            .setPositiveButton("أرشفة") { _, _ -> db.setArchived(space.id, true); showHome() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun confirmDeleteSpace(space: SpaceRow) {
        AlertDialog.Builder(this).setTitle("حذف «${space.title}»؟")
            .setMessage("سيتم حذف محتوى هذه المساحة من النسخة التجريبية.")
            .setPositiveButton("حذف") { _, _ -> db.deleteSpace(space.id); renderSpaceList() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun showMessageMenu(anchor: View, m: MessageRow) {
        PopupMenu(this, anchor).apply {
            if (m.kind == "file") {
                if (m.filePath != null) menu.add("فتح")
                if (!m.ocrText.isNullOrBlank()) menu.add("نسخ النص")
            } else {
                menu.add("نسخ")
            }
            menu.add(if (m.starred) "إلغاء النجمة" else "تمييز بنجمة ★")
            menu.add("نقل إلى مساحة أخرى")
            menu.add("مشاركة")
            menu.add("حذف")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "فتح" -> openSavedFile(m)
                    "نسخ", "نسخ النص" -> copyMessage(m)
                    "تمييز بنجمة ★" -> {
                        db.setMessageStarred(m.id, true)
                        currentSpaceId?.let(::renderMessages)
                    }
                    "إلغاء النجمة" -> {
                        db.setMessageStarred(m.id, false)
                        currentSpaceId?.let(::renderMessages)
                    }
                    "نقل إلى مساحة أخرى" -> showMoveMessageDialog(m)
                    "مشاركة" -> shareMessage(m)
                    "حذف" -> confirmDeleteMessage(m)
                }
                true
            }
            show()
        }
    }

    private fun copyMessage(m: MessageRow) {
        val value = when {
            m.kind == "file" && !m.ocrText.isNullOrBlank() -> m.ocrText
            m.kind == "file" -> m.displayName.orEmpty()
            else -> m.text
        }.orEmpty()
        if (value.isBlank()) {
            Toast.makeText(this, "لا يوجد نص لنسخه", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Masahati", value))
        Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
    }

    private fun shareMessage(m: MessageRow) {
        if (m.kind == "file" && m.filePath != null) {
            val file = File(m.filePath)
            if (!file.exists()) {
                Toast.makeText(this, "الملف غير موجود", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = m.mimeType ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "مشاركة الملف"))
            return
        }

        val value = if (m.kind == "file") m.ocrText.orEmpty() else m.text
        if (value.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }
        startActivity(Intent.createChooser(intent, "مشاركة"))
    }

    private fun showMoveMessageDialog(m: MessageRow) {
        val targets = (db.listSpaces(false) + db.listSpaces(true))
            .distinctBy { it.id }
            .filter { it.id != m.spaceId }
        if (targets.isEmpty()) {
            Toast.makeText(this, "أنشئ مساحة أخرى أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val names = targets.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("نقل إلى")
            .setItems(names) { _, which ->
                val target = targets[which]
                db.moveMessage(m.id, target.id)
                currentSpaceId?.let(::renderMessages)
                Toast.makeText(this, "تم النقل إلى «${target.title}»", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showStarredMessages(spaceId: Long) {
        val starred = db.listStarred(spaceId)
        if (starred.isEmpty()) {
            Toast.makeText(this, "لا توجد عناصر مميزة في هذه المساحة", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = starred.map { item ->
            when {
                item.kind == "file" -> "★ " + (item.displayName ?: "ملف")
                item.text.isNotBlank() -> "★ " + item.text.replace("\n", " ").take(90)
                else -> "★ عنصر مميز"
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("العناصر المميزة ★")
            .setItems(labels) { _, which -> copyMessage(starred[which]) }
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun confirmDeleteMessage(m: MessageRow) {
        AlertDialog.Builder(this).setTitle("حذف هذا العنصر؟")
            .setPositiveButton("حذف") { _, _ ->
                m.filePath?.let { runCatching { File(it).delete() } }
                db.deleteMessage(m.id)
                currentSpaceId?.let { renderMessages(it) }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        val c = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) ?: return null
        return c.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun safeFileName(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._\\-ء-ي]"), "_").take(100).ifBlank { "file" }
        return "${System.currentTimeMillis()}-$clean"
    }

    private fun formatTime(epoch: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch))

    private fun setBusyToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun button(label: String, size: Float) = Button(this).apply {
        text = label
        textSize = size
        setTextColor(Color.rgb(35, 40, 40))
        isAllCaps = false
        background = rounded(controlBg, 4f)
        setPadding(0, 0, 0, 0)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setLineSpacing(0f, 1.08f)
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int = Color.TRANSPARENT, strokeWidthDp: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        if (strokeWidthDp > 0 && stroke != Color.TRANSPARENT) setStroke(dp(strokeWidthDp), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add)
    }

    companion object {
        private const val SPACE_LIST_ID = 4001
        private const val MESSAGE_LIST_ID = 4002
        private const val AGENT_URL = "https://hxrvlvqlkfylbjicdfzs.supabase.co/functions/v1/masahati-agent-dev"
        private const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_BPVsQQO6jXMCp9sx-OadWg_sVGbD7Y3"
    }
}
