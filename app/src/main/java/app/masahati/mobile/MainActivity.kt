package app.masahati.mobile

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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
import androidx.core.content.FileProvider
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentSpaceId != null) showHome() else finish()
            }
        })
        showHome()
        intent.getLongExtra("open_space_id", -1L).takeIf { it > 0L }?.let { openSpace(it) }
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
            val waiting = MessageRow(-1, spaceId, "assistant", "text", "جاري الفهم والترتيب…", null, null, null, null, null, null, null, System.currentTimeMillis())
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
            background = rounded(if (m.role == "assistant") assistantBg else paleTeal, 25f, if (m.role == "assistant") Color.rgb(210, 214, 211) else Color.TRANSPARENT, if (m.role == "assistant") 1 else 0)
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
                bubble.setOnLongClickListener { confirmDeleteMessage(m); true }
            }
        } else {
            bubble.addView(text(m.text, 18f, Color.rgb(30, 35, 35), false))
            if (!temporary && m.id > 0) bubble.setOnLongClickListener { confirmDeleteMessage(m); true }
        }
        if (m.id > 0 && !temporary && m.role != "assistant") {
            val meta = listOfNotNull(m.classification, m.tags).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) bubble.addView(text(meta.take(120), 12f, teal, false))
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
            val recent = db.recentForAi(spaceId, 10).filter { it.id != messageId }
            try {
                val body = JSONObject().apply {
                    put("text", content.take(5000))
                    put("spaceTitle", spaceTitle)
                    put("now", ZonedDateTime.now().toString())
                    put("timezone", java.time.ZoneId.systemDefault().id)
                    val spaces = JSONArray()
                    db.listSpaces(false).forEach { spaces.put(it.title) }
                    put("spaces", spaces)
                    val arr = JSONArray()
                    recent.forEach { msg ->
                        val contextText = if (msg.kind == "file") {
                            buildString {
                                append("مستند سابق")
                                msg.displayName?.takeIf { it.isNotBlank() }?.let { append("؛ الاسم: ").append(it) }
                                msg.summary?.takeIf { it.isNotBlank() }?.let { append("؛ الملخص السابق: ").append(it) }
                                msg.tags?.takeIf { it.isNotBlank() }?.let { append("؛ الوسوم: ").append(it) }
                                msg.ocrText?.takeIf { it.isNotBlank() }?.let { append("؛ النص المقروء: ").append(it) }
                            }
                        } else {
                            msg.text
                        }
                        arr.put(JSONObject().apply {
                            put("role", if (msg.role == "assistant") "assistant" else "user")
                            put("text", contextText.take(2200))
                        })
                    }
                    put("recent", arr)
                }
                val remote = runCatching { postAgent(body) }.getOrNull()
                val result = if (remote?.optBoolean("ok", false) == true && remote.optString("engine") == "remote-ai") {
                    remote
                } else {
                    LocalAssistantFallback.analyze(content, spaceTitle, recent)
                }
                val labelList = result.optJSONArray("labels")?.toStringList().orEmpty()
                val keywordList = result.optJSONArray("keywords")?.toStringList().orEmpty()
                val searchTags = (labelList + keywordList).filter { it.isNotBlank() }.distinct().take(16).joinToString("، ")
                val classification = result.optString("classification", "other")
                val summary = result.optString("summary", "")
                db.updateAi(messageId, classification, searchTags, summary, result.toString())
                val actionText = executeAgentActions(spaceId, result.optJSONArray("actions"), content)
                val reply = result.optString("reply", "فهمت المحتوى وحفظته.")
                db.insertText(spaceId, "assistant", listOf(reply, actionText).filter { it.isNotBlank() }.joinToString("\n\n"))
            } catch (_: Exception) {
                val fallback = LocalAssistantFallback.analyze(content, spaceTitle, recent)
                db.updateAi(messageId, fallback.optString("classification", "note"), fallback.optJSONArray("labels")?.toStringList()?.joinToString("، ").orEmpty(), fallback.optString("summary", content.take(220)), fallback.toString())
                db.insertText(spaceId, "assistant", fallback.optString("reply", "فهمت المحتوى وحفظته كملاحظة قابلة للبحث."))
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

    private fun executeAgentActions(spaceId: Long, actions: JSONArray?, sourceText: String): String {
        if (actions == null) return ""
        val notes = mutableListOf<String>()
        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            val type = action.optString("type")
            val args = action.optJSONObject("args") ?: JSONObject()
            val needsConfirm = action.optBoolean("requires_confirmation", true)
            when (type) {
                "create_reminder" -> {
                    val created = ReminderScheduler.createFromAgent(this, db, spaceId, args, sourceText)
                    if (created == null) {
                        notes += "فهمت أنك تريد تنبيهاً، لكن الموعد غير واضح بما يكفي لإنشائه."
                    } else {
                        val precision = if (created.exact) "" else " قد يتأخر بضع دقائق ما لم تفعّل دقة التنبيهات من إعدادات أندرويد."
                        val permissionNote = if (ReminderScheduler.notificationsAllowed(this)) "" else " سأطلب منك الآن السماح بإشعارات التطبيق."
                        notes += "تم إنشاء تنبيه فعلي: ${created.description}.$precision$permissionNote"
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
                        notes += "ربطت هذه المعلومة بالمستند السابق وحدّثت وصفه وكلمات البحث."
                    }
                }
                "search" -> {
                    val q = args.optString("query").trim()
                    if (q.isNotBlank()) {
                        val found = db.search(q, 8)
                        if (found.isEmpty()) notes += "لم أجد نتيجة محلية مطابقة لـ «$q»."
                        else {
                            val lines = found.mapNotNull { m ->
                                val s = db.getSpace(m.spaceId)?.title ?: return@mapNotNull null
                                val preview = when {
                                    !m.displayName.isNullOrBlank() -> m.displayName
                                    m.summary?.isNotBlank() == true -> m.summary
                                    else -> m.text.take(85)
                                }
                                "• $s — $preview"
                            }
                            notes += "وجدت محلياً:\n${lines.joinToString("\n")}"
                        }
                    }
                }
                "archive_space" -> {
                    val target = args.optString("space_name").ifBlank { db.getSpace(spaceId)?.title.orEmpty() }
                    val space = db.findSpaceByTitle(target) ?: db.getSpace(spaceId)
                    if (space != null) {
                        if (needsConfirm) notes += "الأرشفة جاهزة، لكني لم أنفذها لأن الإجراء يحتاج تأكيداً."
                        else {
                            db.setArchived(space.id, true)
                            notes += "تمت أرشفة مساحة «${space.title}»."
                        }
                    }
                }
                "pin_space" -> {
                    val target = args.optString("space_name").ifBlank { db.getSpace(spaceId)?.title.orEmpty() }
                    val space = db.findSpaceByTitle(target) ?: db.getSpace(spaceId)
                    if (space != null) {
                        if (needsConfirm) notes += "التثبيت جاهز وينتظر التأكيد."
                        else {
                            db.setPinned(space.id, true)
                            notes += "تم تثبيت مساحة «${space.title}»."
                        }
                    }
                }
                "create_space" -> {
                    val title = args.optString("title").trim()
                    if (title.isNotBlank()) {
                        val existing = db.findSpaceByTitle(title)
                        if (existing != null) notes += "مساحة «${existing.title}» موجودة أصلاً."
                        else if (needsConfirm) notes += "إنشاء مساحة «$title» جاهز وينتظر التأكيد."
                        else {
                            db.createSpace(title)
                            notes += "تم إنشاء مساحة «$title»."
                        }
                    }
                }
                "rename_space" -> {
                    val newName = args.optString("new_name").ifBlank { args.optString("title") }.trim()
                    if (newName.isNotBlank()) {
                        if (needsConfirm) notes += "إعادة التسمية جاهزة وينتظر التأكيد."
                        else {
                            db.renameSpace(spaceId, newName)
                            notes += "تم تغيير اسم المساحة إلى «$newName»."
                        }
                    }
                }
                "move_last_item" -> {
                    val targetName = args.optString("target_space").ifBlank { args.optString("space_name") }.trim()
                    val target = db.findSpaceByTitle(targetName)
                    val last = db.lastUserMessage(spaceId)
                    if (target != null && last != null) {
                        if (needsConfirm) notes += "النقل جاهز وينتظر التأكيد."
                        else {
                            db.moveMessage(last.id, target.id)
                            notes += "تم نقل آخر عنصر إلى «${target.title}»."
                        }
                    }
                }
            }
        }
        return notes.joinToString("\n")
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
            readTimeout = 13_000
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
            menu.add("أرشفة")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "تثبيت" -> { db.setPinned(space.id, true); Toast.makeText(this@MainActivity, "تم التثبيت", Toast.LENGTH_SHORT).show() }
                    "إلغاء التثبيت" -> { db.setPinned(space.id, false); Toast.makeText(this@MainActivity, "تم إلغاء التثبيت", Toast.LENGTH_SHORT).show() }
                    "إعادة تسمية" -> promptRename(space)
                    "بحث شامل" -> promptGlobalSearch()
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
