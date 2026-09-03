package app.masahati.mobile

import org.json.JSONArray
import org.json.JSONObject

object LocalAssistantFallback {
    fun analyze(text: String, spaceTitle: String, recent: List<MessageRow> = emptyList()): JSONObject {
        val raw = text.trim()
        val lower = raw.lowercase()
        val context = recent.takeLast(8).joinToString(" ") { (it.text.ifBlank { it.ocrText.orEmpty() }) }.lowercase()
        val previousFile = recent.asReversed().firstOrNull { it.kind == "file" }
        val labels = linkedSetOf<String>()
        val keywords = linkedSetOf<String>()
        val actions = JSONArray()

        fun has(vararg words: String) = words.any { lower.contains(it) }
        fun addLabel(value: String) { if (value.isNotBlank()) labels += value }
        fun addKeyword(value: String) { if (value.isNotBlank()) keywords += value }

        val time = Regex("(?:[01]?\\d|2[0-3])[:.]\\d{2}").find(raw)?.value?.replace('.', ':')
            ?: Regex("(?:[01]?\\d|2[0-3])\\s*(?:ص|م)").find(raw)?.value
            ?: Regex("(?:[01]?\\d|2[0-3])[:.]\\d{2}").find(context)?.value?.replace('.', ':')
            ?: Regex("(?:[01]?\\d|2[0-3])\\s*(?:ص|م)").find(context)?.value
        val days = listOf("الاثنين", "الإثنين", "اثنين", "الثلاثاء", "ثلاثاء", "الأربعاء", "الاربعاء", "أربعاء", "اربعاء", "الخميس", "خميس", "الجمعة", "جمعة", "السبت", "سبت", "الأحد", "الاحد", "أحد", "احد")
        val day = days.firstOrNull { lower.contains(it) } ?: days.firstOrNull { context.contains(it) }
        if (time != null) addKeyword(time)
        if (day != null) addKeyword(day)

        val classification: String
        val reply: String
        when {
            previousFile != null && has("هي ورقة", "هاي ورقة", "هاي الورقة", "هاد المستند", "هذا المستند", "هذه الورقة", "الورقة هي", "الوثيقة هي") -> {
                classification = "document"
                addLabel("مستند")
                raw.split(Regex("[^\\p{L}\\p{N}]+"))
                    .map { it.trim() }
                    .filter { it.length >= 3 }
                    .take(10)
                    .forEach(::addKeyword)
                actions.put(JSONObject().apply {
                    put("type", "enrich_previous_document")
                    put("requires_confirmation", false)
                    put("args", JSONObject().apply {
                        put("summary", raw.take(500))
                        put("labels", JSONArray(labels.toList()))
                        put("keywords", JSONArray(keywords.toList()))
                    })
                })
                reply = "فهمت أن كلامك يشرح المستند السابق، وسأربط الوصف به بدل حفظه كملاحظة منفصلة."
            }
            has("وين", "أين", "اين", "ابحث", "دور", "فتش", "find ", "suche", "wo ist") -> {
                classification = "search"
                val q = raw
                    .replace(Regex("^(وين|أين|اين|ابحث عن|دور على|فتش عن)\\s*"), "")
                    .replace(Regex("^(حطيت|حطيتلي|وضعت|حفظت|خزنت)\\s*"), "")
                    .replace(Regex("[؟?]+$"), "")
                    .trim().ifBlank { raw }
                actions.put(JSONObject().put("type", "search").put("args", JSONObject().put("query", q)).put("requires_confirmation", false))
                reply = "سأبحث داخل مساحاتك عن «$q» وأعرض لك أقرب النتائج."
                addLabel("بحث")
                addKeyword(q.take(80))
            }
            has("أرشف", "ارشف", "أرشفة", "ارشفة") -> {
                classification = "command"
                actions.put(JSONObject().put("type", "archive_space").put("args", JSONObject().put("space_name", spaceTitle)).put("requires_confirmation", false))
                reply = "فهمت أنك تريد أرشفة هذه المساحة."
                addLabel("أرشفة")
            }
            has("ثبت المساحة", "ثبّت المساحة", "تثبيت المساحة") -> {
                classification = "command"
                actions.put(JSONObject().put("type", "pin_space").put("args", JSONObject().put("space_name", spaceTitle)).put("requires_confirmation", false))
                reply = "فهمت أنك تريد تثبيت هذه المساحة."
                addLabel("تثبيت")
            }
            has("ذكرني", "ذكّرني", "تذكير", "remind", "erinner") -> {
                classification = "reminder"
                addLabel("تذكير")
                if (day != null) addLabel(day)
                val reminderArgs = JSONObject().apply {
                    if (day != null) {
                        put("day_of_week", day)
                        put("repeat", "weekly")
                    }
                    if (time != null) put("time", time)
                    if (has("كل يوم", "يومياً", "يوميا", "daily")) put("repeat", "daily")
                    put("title", "تذكير مساحاتي")
                    put("body", raw.take(500))
                }
                actions.put(JSONObject().put("type", "create_reminder").put("args", reminderArgs).put("requires_confirmation", false))
                reply = when {
                    day != null && time != null -> "فهمت التذكير: $day الساعة $time، وسأنشئ تنبيه أندرويد فعلياً."
                    Regex("بعد\\s+\\d+").containsMatchIn(raw) -> "فهمت التذكير النسبي وسأنشئ له تنبيه أندرويد فعلياً."
                    day != null -> "فهمت أن التذكير مرتبط بـ$day، لكن لا يوجد وقت واضح بعد."
                    time != null -> "فهمت وقت التذكير $time، لكن اليوم أو التاريخ غير واضح بعد."
                    else -> "فهمت أنك تريد تذكيراً، لكن أحتاج اليوم أو الوقت حتى يكون محدداً."
                }
            }
            has("دوام", "شفت", "مناوبة", "arbeit", "schicht") -> {
                classification = "work_schedule"
                addLabel("دوام")
                if (day != null) addLabel(day)
                reply = when {
                    day != null && time != null -> "سجلت أن هذا متعلق بالدوام: $day الساعة $time، وسأجعله قابلاً للبحث بهذه الكلمات."
                    else -> "فهمت أنها معلومة مرتبطة بالدوام وحفظتها للتصنيف والبحث."
                }
            }
            has("جواز", "عقد", "فاتورة", "وثيقة", "مستند", "pdf", "rechnung", "vertrag", "pass") -> {
                classification = "document"
                addLabel("مستند")
                listOf("جواز", "عقد", "فاتورة", "وثيقة").firstOrNull { lower.contains(it) }?.let(::addLabel)
                reply = "فهمت أنه مستند. حفظت وصفه وكلمات البحث محلياً حتى يسهل العثور عليه لاحقاً."
            }
            has("فكرة", "مشروع", "idea") -> {
                classification = "idea"
                addLabel("فكرة")
                reply = "حفظت الفكرة وسأبقيها مصنفة لتظهر بسهولة في البحث لاحقاً."
            }
            has("لازم", "مهمة", "اعمل", "أعمل", "task", "todo") -> {
                classification = "task"
                addLabel("مهمة")
                reply = "فهمت أنها مهمة، وحفظتها بهذا التصنيف حتى لا تضيع بين الملاحظات."
            }
            else -> {
                classification = "note"
                addLabel("ملاحظة")
                reply = "فهمت المحتوى وحفظته كملاحظة قابلة للبحث."
            }
        }

        raw.split(Regex("[^\\p{L}\\p{N}:.]+"))
            .map { it.trim() }
            .filter { it.length >= 3 }
            .take(8)
            .forEach(::addKeyword)

        return JSONObject()
            .put("ok", true)
            .put("engine", "local-fallback")
            .put("reply", reply)
            .put("classification", classification)
            .put("labels", JSONArray(labels.toList().take(8)))
            .put("keywords", JSONArray(keywords.toList().take(12)))
            .put("summary", raw.take(320))
            .put("confidence", 0.62)
            .put("actions", actions)
    }
}
