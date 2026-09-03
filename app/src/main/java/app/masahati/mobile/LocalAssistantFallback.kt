package app.masahati.mobile

import org.json.JSONArray
import org.json.JSONObject

object LocalAssistantFallback {
    fun analyze(text: String, spaceTitle: String, recent: List<MessageRow> = emptyList()): JSONObject {
        val raw = text.trim()
        val lower = raw.lowercase()
        val context = recent.takeLast(6).joinToString(" ") { (it.text.ifBlank { it.ocrText.orEmpty() }) }.lowercase()
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
            (has("مساحة جديدة", "مساحه جديدة", "مساحة جديده") && has("ضعه", "حطه", "حطها", "انقله", "انقلها", "المستند", "العقد", "الملف")) -> {
                classification = "command"
                val targetName = Regex("(?:اسمها|سميها|وسميها|اسم المساحة)\\s*[«\\\"']?([^،,.!؟?\\n]{1,50})", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.getOrNull(1)?.trim()?.trim('«','»','\"','\'')
                    ?.ifBlank { null } ?: "عقود"
                actions.put(JSONObject().put("type", "create_space").put("args", JSONObject().put("name", targetName)).put("requires_confirmation", false))
                actions.put(JSONObject().put("type", "move_last_item").put("args", JSONObject().put("target_space", targetName).put("prefer_file", true).put("create_if_missing", true)).put("requires_confirmation", false))
                reply = "فهمت: سأنشئ مساحة «$targetName» إن لم تكن موجودة، وأنقل المستند السابق إليها."
                addLabel("تنظيم")
                addKeyword(targetName)
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
            recent.any { it.kind == "file" } && (has("هي ", "هو ", "هذا", "هاد", "هالورقة", "الورقة", "الورقه", "المستند السابق")) -> {
                classification = "document"
                addLabel("توضيح مستند")
                actions.put(JSONObject().put("type", "enrich_previous_document").put("args", JSONObject()
                    .put("summary", raw.take(320))
                    .put("labels", JSONArray(listOf("توضيح مستند")))
                    .put("keywords", JSONArray(raw.split(Regex("[^\\p{L}\\p{N}]+")) .filter { it.length >= 3 }.take(8))))
                    .put("requires_confirmation", false))
                reply = "ربطت كلامك بالمستند السابق وسأحدّث وصفه وكلمات البحث بدل اعتباره ملاحظة منفصلة."
            }
            has("جواز", "عقد", "فاتورة", "وثيقة", "مستند", "pdf", "rechnung", "vertrag", "pass") -> {
                classification = "document"
                addLabel("مستند")
                val isContract = has("عقد", "vertrag", "contract", "kündigung", "kuendigung")
                if (isContract) addLabel("عقد")
                listOf("جواز", "عقد", "فاتورة", "وثيقة").firstOrNull { lower.contains(it) }?.let(::addLabel)
                val hasDate = Regex("\\b\\d{1,2}[./-]\\d{1,2}(?:[./-]\\d{2,4})?\\b").containsMatchIn(raw)
                reply = if (isContract && !hasDate) {
                    "هذا يبدو عقداً، لكني لم أجد تاريخاً واضحاً للبدء أو الانتهاء/الإلغاء. إذا بدك أذكرك بموعد الإلغاء، قلّي التاريخ والوقت وسأنشئ التذكير."
                } else {
                    "فهمت أنه مستند. حفظت وصفه وكلمات البحث محلياً حتى يسهل العثور عليه لاحقاً."
                }
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
