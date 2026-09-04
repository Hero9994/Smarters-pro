package app.masahati.mobile

import org.json.JSONArray
import org.json.JSONObject

object LocalAssistantFallback {
    fun analyze(text: String, spaceTitle: String, recent: List<MessageRow> = emptyList()): JSONObject {
        val raw = text.trim()
        val lower = raw.lowercase()
        val context = recent.takeLast(12).joinToString(" ") {
            listOf(it.text, it.summary.orEmpty(), it.tags.orEmpty(), it.ocrText.orEmpty(), it.displayName.orEmpty()).joinToString(" ")
        }.lowercase()
        val labels = linkedSetOf<String>()
        val keywords = linkedSetOf<String>()
        val actions = JSONArray()
        val recentDocument = recent.lastOrNull { it.kind == "file" && (!it.ocrText.isNullOrBlank() || !it.summary.isNullOrBlank()) }
        val documentContext = recentDocument?.let {
            listOfNotNull(it.summary, it.tags, it.ocrText, it.displayName).joinToString(" ")
        }.orEmpty()

        fun has(vararg words: String) = words.any { lower.contains(it) }
        fun addLabel(value: String) { if (value.isNotBlank()) labels += value }
        fun addKeyword(value: String) { if (value.isNotBlank()) keywords += value }

        fun capture(pattern: String): String? = Regex(pattern, RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('«', '»', '"')
            ?.takeIf { it.isNotBlank() }

        val createSpaceName = capture("""(?:أنشئ|انشئ|اعمل|سوي|سوّي)\s+(?:لي\s+)?مساحة(?:\s+جديدة)?(?:\s+باسم|\s+اسمها)?\s+(.+)$""")
            ?: capture("""create\s+(?:a\s+)?space(?:\s+called|\s+named)?\s+(.+)$""")
            ?: capture("""erstelle\s+(?:einen\s+)?bereich(?:\s+namens)?\s+(.+)$""")
        val renameSpaceName = capture("""(?:غير|غيّر|بدل|بدّل)\s+اسم\s+(?:هالمساحة|المساحة)(?:\s+إلى|\s+الى|\s+لـ?)\s+(.+)$""")
            ?: capture("""rename\s+(?:this\s+)?space\s+(?:to\s+)?(.+)$""")
        val renameDocumentName = capture("""(?:غير|غيّر|بدل|بدّل)\s+اسم\s+(?:الملف|المستند|الورقة)(?:\s+إلى|\s+الى|\s+لـ?)\s+(.+)$""")
            ?: capture("""rename\s+(?:the\s+)?(?:file|document)\s+(?:to\s+)?(.+)$""")
        val moveDocumentTarget = capture("""(?:انقل|نقل|حرّك|حرك)\s+(?:هالورقة|الورقة|الملف|المستند)(?:\s+إلى|\s+الى|\s+على)\s+(.+)$""")
            ?: capture("""move\s+(?:the\s+)?(?:file|document)\s+to\s+(.+)$""")
        val moveLastTarget = capture("""(?:انقل|نقل|حرّك|حرك)\s+(?:آخر|اخر)\s+(?:شي|شيء|عنصر)(?:\s+إلى|\s+الى|\s+على)\s+(.+)$""")
            ?: capture("""move\s+(?:the\s+)?last\s+(?:item|message)\s+to\s+(.+)$""")

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
            recentDocument != null && has("متى", "تاريخ", "انتهاء", "ينتهي", "بينتهي", "تنتهي", "ende", "ablauf", "gültig bis") -> {
                classification = "document"
                addLabel("مستند")
                val dates = Regex("(?:0?[1-9]|[12]\\d|3[01])[./-](?:0?[1-9]|1[0-2])[./-](?:19|20)\\d{2}")
                    .findAll(documentContext)
                    .map { it.value }
                    .toList()
                reply = if (dates.isNotEmpty()) {
                    "التاريخ الظاهر في المستند هو " + dates.last() + "."
                } else if (!recentDocument.summary.isNullOrBlank()) {
                    "لا أرى تاريخاً واضحاً في النص المستخرج. ملخص المستند: " + recentDocument.summary!!.take(260)
                } else {
                    "لا أرى تاريخاً واضحاً في النص المستخرج من المستند السابق."
                }
            }
            recentDocument != null && has("شو فيها", "شو فيه", "شو مكتوب", "هاد شو", "هاي شو", "ما هذا", "ما هذه", "was ist", "worum geht") -> {
                classification = "document"
                addLabel("مستند")
                val docSummary = recentDocument.summary?.trim().orEmpty()
                val docOcr = recentDocument.ocrText?.trim().orEmpty()
                reply = when {
                    docSummary.isNotBlank() -> docSummary.take(420)
                    docOcr.isNotBlank() -> "المكتوب الظاهر في المستند: " + docOcr.replace("\n", " ").take(420)
                    else -> "المستند السابق محفوظ، لكن النص المقروء منه غير كافٍ للإجابة."
                }
            }
            recentDocument != null && has("هي ورقة", "هاي ورقة", "هاي الورقة", "هاد المستند", "هذا المستند", "هذه الورقة", "نفس الورقة") -> {
                classification = "document"
                addLabel("مستند")
                val useful = raw.split(Regex("[^\\p{L}\\p{N}]+"))
                    .map { it.trim() }
                    .filter { it.length >= 3 }
                    .distinct()
                    .take(10)
                useful.forEach(::addKeyword)
                actions.put(
                    JSONObject()
                        .put("type", "enrich_previous_document")
                        .put(
                            "args",
                            JSONObject()
                                .put("summary", raw.take(500))
                                .put("labels", JSONArray(listOf("مستند")))
                                .put("keywords", JSONArray(useful))
                        )
                        .put("requires_confirmation", false)
                )
                reply = "ربطت وصفك بالمستند السابق حتى يفهمه البحث لاحقاً."
            }
            createSpaceName != null -> {
                classification = "command"
                addLabel("مساحة")
                actions.put(
                    JSONObject()
                        .put("type", "create_space")
                        .put("args", JSONObject().put("name", createSpaceName))
                        .put("requires_confirmation", false)
                )
                reply = "فهمت أنك تريد إنشاء مساحة «$createSpaceName»."
            }
            renameSpaceName != null -> {
                classification = "command"
                addLabel("إعادة تسمية")
                actions.put(
                    JSONObject()
                        .put("type", "rename_space")
                        .put("args", JSONObject().put("new_name", renameSpaceName))
                        .put("requires_confirmation", false)
                )
                reply = "فهمت أنك تريد تغيير اسم هذه المساحة إلى «$renameSpaceName»."
            }
            renameDocumentName != null -> {
                classification = "command"
                addLabel("مستند")
                actions.put(
                    JSONObject()
                        .put("type", "rename_last_document")
                        .put("args", JSONObject().put("new_name", renameDocumentName))
                        .put("requires_confirmation", false)
                )
                reply = "فهمت أنك تريد تغيير اسم آخر مستند إلى «$renameDocumentName»."
            }
            moveDocumentTarget != null -> {
                classification = "command"
                addLabel("نقل مستند")
                actions.put(
                    JSONObject()
                        .put("type", "move_last_document")
                        .put("args", JSONObject().put("target_space", moveDocumentTarget))
                        .put("requires_confirmation", false)
                )
                reply = "فهمت أنك تريد نقل آخر مستند إلى مساحة «$moveDocumentTarget»."
            }
            moveLastTarget != null -> {
                classification = "command"
                addLabel("نقل")
                actions.put(
                    JSONObject()
                        .put("type", "move_last_item")
                        .put("args", JSONObject().put("target_space", moveLastTarget))
                        .put("requires_confirmation", false)
                )
                reply = "فهمت أنك تريد نقل آخر عنصر إلى مساحة «$moveLastTarget»."
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
