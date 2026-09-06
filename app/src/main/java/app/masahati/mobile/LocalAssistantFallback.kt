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

        fun extractClock(source: String): String? {
            val normalized = source
                .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3').replace('٤', '4')
                .replace('٥', '5').replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9')
            Regex("(?:[01]?\\d|2[0-3])[:.]\\d{2}").find(normalized)?.value?.let { return it.replace('.', ':') }
            val clock = Regex("(?:الساعة|الساعه|at|um)\\s*(\\d{1,2})(?:\\s*(ص|م|صباح|مساء|am|pm))?", RegexOption.IGNORE_CASE)
                .find(normalized) ?: return null
            var hour = clock.groupValues[1].toIntOrNull() ?: return null
            val period = clock.groupValues.getOrNull(2).orEmpty().lowercase()
            if ((period == "م" || period.contains("مساء") || period == "pm") && hour in 1..11) hour += 12
            if ((period == "ص" || period.contains("صباح") || period == "am") && hour == 12) hour = 0
            return if (hour in 0..23) String.format(java.util.Locale.ROOT, "%02d:00", hour) else null
        }
        val time = extractClock(raw) ?: extractClock(context)
        val days = listOf("الاثنين", "الإثنين", "اثنين", "الثلاثاء", "ثلاثاء", "الأربعاء", "الاربعاء", "أربعاء", "اربعاء", "الخميس", "خميس", "الجمعة", "جمعة", "السبت", "سبت", "الأحد", "الاحد", "أحد", "احد")
        val day = days.firstOrNull { lower.contains(it) } ?: days.firstOrNull { context.contains(it) }
        if (time != null) addKeyword(time)
        if (day != null) addKeyword(day)

        val classification: String
        val reply: String
        when {
            Regex(
                "^(?:وين|أين|اين|ابحث(?:لي)?(?:\\s+عن)?|دور(?:لي)?(?:\\s+على)?|فتش(?:لي)?(?:\\s+عن)?|find(?:\\s+me)?|search(?:\\s+for)?|suche(?:\\s+nach)?|wo\\s+ist)(?:\\s+|$)",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(raw) -> {
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
            Regex(
                "^(?:طيب\\s*)?(?:ضد\\s+(?:مين|من)|مين\\s+الخصم|من\\s+الخصم|gegen\\s+wen)\\s*[؟?]?$",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(raw) && recent.isNotEmpty() -> {
                classification = "note"
                addLabel("مباراة")
                val source = recent.asReversed()
                    .map { it.text }
                    .firstOrNull { Regex("(?:ضد|gegen)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                val candidate = source?.let {
                    Regex("(?:ضد|gegen)\\s+([^،,.!؟?\\n]{2,90})", RegexOption.IGNORE_CASE)
                        .find(it)?.groupValues?.getOrNull(1)
                }.orEmpty()
                    .replace(
                        Regex("\\s+(?:يوم|الساعة|الأحد|الاحد|الاثنين|الإثنين|الثلاثاء|الأربعاء|الاربعاء|الخميس|الجمعة|السبت)\\b.*$", RegexOption.IGNORE_CASE),
                        ""
                    )
                    .trim()
                reply = if (candidate.isNotBlank()) {
                    addKeyword(candidate)
                    "حسب آخر معلومة عندي: المباراة ضد $candidate."
                } else {
                    "ما لقيت اسم الخصم بشكل واضح في آخر معلومات المباراة."
                }
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
            recentDocument != null && (
                has("هي ورقة", "هاي ورقة", "هاي الورقة", "هاد المستند", "هذا المستند", "هذه الورقة", "نفس الورقة", "موافقة نقل", "تصريح نقل") ||
                    ((has("مو دوام", "مش دوام", "ليس دوام", "مو جدول دوام", "تصحيح", "قصدي")) &&
                        has("ورقة", "مستند", "موافقة", "تصريح", "نقل", "طبيب"))
            ) -> {
                classification = "document"
                val medicalTransport = Regex(
                    "(نقل|موافقة|تصريح).*(طبيب|دكتور)|krankenbeförder|krankentransport|\\bArzt\\b",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(raw + " " + documentContext)
                val cleanDescription = raw
                    .replace(Regex("^(?:لا\\s+)?(?:مو|مش|ليس)\\s+(?:جدول\\s+)?دوام\\s*[,،:-]*\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("^(?:هاي|هذه|هي|هاد|هذا)\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                val docSummary = if (medicalTransport) {
                    "موافقة/تصريح لنقل المريض من المنزل إلى الطبيب"
                } else cleanDescription.take(500)
                val docLabels = if (medicalTransport) listOf("مستند", "نقل مرضى", "طبيب") else listOf("مستند")
                docLabels.forEach(::addLabel)
                val useful = cleanDescription.split(Regex("[^\\p{L}\\p{N}]+"))
                    .map { it.trim() }
                    .filter { it.length >= 3 }
                    .distinct()
                    .toMutableList()
                if (medicalTransport) useful += listOf("Krankenbeförderung", "Arzt", "Wohnung")
                useful.distinct().take(12).forEach(::addKeyword)
                actions.put(
                    JSONObject()
                        .put("type", "enrich_previous_document")
                        .put(
                            "args",
                            JSONObject()
                                .put("summary", docSummary)
                                .put("labels", JSONArray(docLabels))
                                .put("keywords", JSONArray(keywords.toList()))
                        )
                        .put("requires_confirmation", false)
                )
                reply = if (has("مو دوام", "مش دوام", "ليس دوام", "مو جدول دوام")) {
                    "فهمت التصحيح: المستند السابق ليس جدول دوام؛ هو " + docSummary + ". ربطت الوصف الصحيح بالملف."
                } else {
                    "ربطت وصفك بالمستند السابق: " + docSummary + "."
                }
            }
            Regex("(?:اعمل|أعمل|انشئ|أنشئ|سوي|سوّي|create)\\s*(?:لي)?\\s*(?:مساحة|space)", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> {
                classification = "command"
                addLabel("مساحة")
                val name = raw.substringAfter("اسمها", "")
                    .trim().trim('«', '»', '"', '\'')
                    .ifBlank { raw.substringAfter("باسم", "").trim().trim('«', '»', '"', '\'') }
                    .take(80)
                if (name.isNotBlank()) {
                    addKeyword(name)
                    actions.put(JSONObject().put("type", "create_space").put("args", JSONObject().put("name", name)).put("requires_confirmation", false))
                    reply = "فهمت: إنشاء مساحة جديدة باسم «" + name + "»."
                } else {
                    reply = "فهمت أنك تريد مساحة جديدة. ما الاسم الذي تريده لها؟"
                }
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
            has("فكرة", "idea") -> {
                classification = "idea"
                addLabel("فكرة")
                val idea = raw.replace(
                    Regex("^(?:فكرة(?:\\s+للتطبيق|\\s+للمشروع)?\\s*[:：-]?\\s*)", RegexOption.IGNORE_CASE),
                    ""
                ).trim()
                idea.split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.length >= 3 }
                    .take(8)
                    .forEach(::addKeyword)
                reply = "فهمت الفكرة: " + idea.take(260) + ". صنفتها كفكرة مشروع حتى لا تختلط بالتذكيرات أو المهام."
            }
            Regex("^(?:لازم|ضروري|مهمة|task|todo)(?:\\s|:|$)", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> {
                classification = "task"
                addLabel("مهمة")
                if (has("تأمين", "التأمين", "التامين", "versicherung")) addLabel("تأمين")
                val task = raw.replace(
                    Regex("^(?:لازم|ضروري|مهمة|task|todo)\\s*", RegexOption.IGNORE_CASE),
                    ""
                ).trim()
                task.split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.length >= 3 }
                    .take(8)
                    .forEach(::addKeyword)
                reply = "فهمت أنها مهمة: " + task.take(260) + ". صنفتها كمهمة حتى لا تضيع بين الملاحظات."
            }
            Regex(
                "(?:^|\\s)(?:ذكرني|ذكّرني|ذكريني|ذكّريني|اعمل(?:لي)?\\s+تذكير|أعمل(?:لي)?\\s+تذكير|سوي(?:لي)?\\s+تذكير|remind\\s+me|erinnere\\s+mich)(?:\\s|$)",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(raw) -> {
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
            has("مباراة", "ماتش", "تدريب", "بطولة", "spiel", "training", "turnier") -> {
                classification = "note"
                val eventType = if (has("تدريب", "training")) "تدريب" else "مباراة"
                addLabel(eventType)
                if (day != null) addLabel(day)
                val details = listOfNotNull(day, time?.let { "الساعة " + it }).joinToString(" ")
                reply = if (details.isNotBlank()) {
                    "فهمت " + eventType + ": " + details + ". حفظت التفاصيل والأسماء المهمة للبحث."
                } else {
                    "فهمت أنها معلومة " + eventType + " وحفظت تفاصيلها للبحث."
                }
            }
            has("موعد", "termin", "طبيب", "دكتور", "zahnarzt", "أسنان", "اسنان") && !has("مستند", "ورقة", "فاتورة", "عقد") -> {
                classification = "note"
                addLabel("موعد")
                if (has("طبيب", "دكتور", "arzt", "zahnarzt", "أسنان", "اسنان")) addLabel("طبيب")
                if (day != null) addLabel(day)
                val details = listOfNotNull(day, time?.let { "الساعة " + it }).joinToString(" ")
                reply = if (details.isNotBlank()) {
                    "فهمت الموعد: " + details + ". حفظت نوع الموعد والتفاصيل للبحث."
                } else {
                    "فهمت أنها معلومة موعد وحفظت تفاصيلها للبحث."
                }
            }
            has("دوام", "دوامي", "شفت", "مناوبة", "arbeit", "schicht", "dienstplan", "arbeitszeit") &&
                !has("مو دوام", "مش دوام", "ليس دوام", "مو جدول دوام") -> {
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
            else -> {
                classification = "note"
                addLabel("ملاحظة")
                val useful = raw.split(Regex("[^\\p{L}\\p{N}:+.-]+"))
                    .map { it.trim() }
                    .filter { it.length >= 2 }
                    .filterNot { it.lowercase() in setOf("هذا", "هذه", "هاي", "هاد", "على", "الى", "إلى", "من", "في", "عن", "مع", "كل", "عندي", "عنده", "بدي", "لازم") }
                    .distinct()
                    .take(6)
                useful.forEach(::addKeyword)
                reply = if (useful.isNotEmpty()) {
                    "حفظتها كملاحظة عن " + useful.take(3).joinToString("، ") + "، وفهرست الكلمات المهمة للبحث."
                } else {
                    "حفظت الملاحظة كما هي، ولم أجد فيها تصنيفاً أدق بدون تخمين."
                }
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
