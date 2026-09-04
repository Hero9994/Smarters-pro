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

        val time = Regex("(?:[01]?\\d|2[0-3])[:.]\\d{2}").find(raw)?.value?.replace('.', ':')
            ?: Regex("(?:[01]?\\d|2[0-3])\\s*(?:ص|م)").find(raw)?.value
            ?: Regex("(?:[01]?\\d|2[0-3])[:.]\\d{2}").find(context)?.value?.replace('.', ':')
            ?: Regex("(?:[01]?\\d|2[0-3])\\s*(?:ص|م)").find(context)?.value
        val days = listOf("الاثنين", "الإثنين", "اثنين", "الثلاثاء", "ثلاثاء", "الأربعاء", "الاربعاء", "أربعاء", "اربعاء", "الخميس", "خميس", "الجمعة", "جمعة", "السبت", "سبت", "الأحد", "الاحد", "أحد", "احد")
        val day = days.firstOrNull { lower.contains(it) } ?: days.firstOrNull { context.contains(it) }
        val relativeMinutes = Regex("بعد\\s+(\\d{1,5})\\s*(?:دقيقة|دقائق|دقايق|دقيقه)", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("بعد\\s+(\\d{1,3})\\s*(?:ساعة|ساعات|ساعه)", RegexOption.IGNORE_CASE)
                .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.times(60)
        val tomorrow = has("بكرا", "غداً", "غدا", "morgen", "tomorrow")
        val medicalTransportSignal =
            Regex("(نقل|توصيل|مواصلات).{0,50}(طبيب|دكتور|مشفى|مستشفى|عيادة)", RegexOption.IGNORE_CASE).containsMatchIn(raw) ||
            Regex("(طبيب|دكتور|مشفى|مستشفى|عيادة).{0,50}(نقل|توصيل|مواصلات)", RegexOption.IGNORE_CASE).containsMatchIn(raw) ||
            Regex("(krankenbeförderung|krankenbefoerderung|krankentransport|transportschein|arztfahrt|fahrtkosten|wohnort.{0,40}arzt)", RegexOption.IGNORE_CASE).containsMatchIn(raw + " " + documentContext)
        val looksLikeDocumentPayload =
            has("مستند ممسوح", "النص المستخرج", "scan-", ".pdf", "ocr") ||
            Regex("(krankenbeförderung|krankenbefoerderung|transportschein|rechnung|vertrag|bescheid|genehmigung)", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        if (time != null) addKeyword(time)
        if (day != null) addKeyword(day)
        if (tomorrow) addKeyword("غداً")

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
            medicalTransportSignal && (recentDocument != null || looksLikeDocumentPayload) -> {
                classification = "document"
                val docLabels = listOf("مستند", "نقل طبي", "مواصلات مرضى")
                docLabels.forEach(::addLabel)
                val semantic = listOf("نقل طبي", "طبيب", "من المنزل", "Krankenbeförderung", "Transport")
                semantic.forEach(::addKeyword)
                val summaryText = when {
                    raw.contains("النص المستخرج", ignoreCase = true) ->
                        "مستند يخص النقل الطبي من المنزل إلى الطبيب."
                    else -> raw.take(500)
                }
                actions.put(
                    JSONObject()
                        .put("type", "enrich_previous_document")
                        .put(
                            "args",
                            JSONObject()
                                .put("summary", summaryText)
                                .put("labels", JSONArray(docLabels))
                                .put("keywords", JSONArray(semantic))
                        )
                        .put("requires_confirmation", false)
                )
                actions.put(
                    JSONObject()
                        .put("type", "rename_previous_document")
                        .put("args", JSONObject().put("display_name", "نقل طبي إلى الطبيب.pdf"))
                        .put("requires_confirmation", false)
                )
                reply = "فهمت أن المستند يخص النقل الطبي من المنزل إلى الطبيب، وصنفته بهذا المعنى حتى يسهل العثور عليه."
            }
            recentDocument != null && has("هي ورقة", "هاي ورقة", "هاي الورقة", "هاد المستند", "هذا المستند", "هذه الورقة", "نفس الورقة") -> {
                classification = "document"
                val combinedDoc = "$raw $documentContext".lowercase()
                val medicalTransport =
                    Regex("(نقل|توصيل|مواصلات).{0,40}(طبيب|دكتور|مشفى|مستشفى|عيادة)", RegexOption.IGNORE_CASE).containsMatchIn(raw) ||
                    Regex("(طبيب|دكتور|مشفى|مستشفى|عيادة).{0,40}(نقل|توصيل|مواصلات)", RegexOption.IGNORE_CASE).containsMatchIn(raw) ||
                    Regex("(krankenbeförderung|krankentransport|transportschein|arztfahrt|fahrtkosten|transport.{0,30}arzt)", RegexOption.IGNORE_CASE).containsMatchIn(combinedDoc)
                val docLabels = if (medicalTransport) listOf("مستند", "نقل طبي", "مواصلات مرضى") else listOf("مستند")
                docLabels.forEach(::addLabel)
                val semantic = if (medicalTransport) listOf("نقل طبي", "طبيب", "من المنزل", "Krankenbeförderung", "Transport") else emptyList()
                val useful = raw.split(Regex("[^\\p{L}\\p{N}]+"))
                    .map { it.trim() }
                    .filter {
                        it.length >= 3 &&
                            it !in setOf("هذه", "هذا", "هاي", "هاد", "ورقة", "المستند", "الورقة", "إلى", "الى", "من")
                    }
                    .distinct()
                    .take(8)
                (semantic + useful).distinct().take(12).forEach(::addKeyword)
                actions.put(
                    JSONObject()
                        .put("type", "enrich_previous_document")
                        .put(
                            "args",
                            JSONObject()
                                .put("summary", raw.take(500))
                                .put("labels", JSONArray(docLabels))
                                .put("keywords", JSONArray((semantic + useful).distinct().take(12)))
                        )
                        .put("requires_confirmation", false)
                )
                reply = if (medicalTransport) {
                    "فهمت أن المستند السابق يخص النقل الطبي من المنزل إلى الطبيب، وربطت الوصف به حتى يظهر بالبحث الصحيح."
                } else {
                    "ربطت وصفك بالمستند السابق حتى يفهمه البحث لاحقاً."
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
            has("ذكرني", "ذكّرني", "تذكير", "remind", "erinner") -> {
                classification = "reminder"
                addLabel("تذكير")
                if (day != null) addLabel(day)
                val isDaily = has("كل يوم", "يومياً", "يوميا", "daily", "täglich", "taeglich")
                val reminderArgs = JSONObject().apply {
                    put("title", "تذكير مساحاتي")
                    put("body", raw.take(500))
                    if (relativeMinutes != null) put("delay_minutes", relativeMinutes)
                    if (day != null) {
                        put("day_of_week", day)
                        put("repeat", "weekly")
                    }
                    if (time != null) put("time", time)
                    if (isDaily) put("repeat", "daily")
                }
                val resolved = relativeMinutes != null || (time != null && (day != null || isDaily))
                if (resolved) {
                    actions.put(
                        JSONObject()
                            .put("type", "create_reminder")
                            .put("args", reminderArgs)
                            .put("requires_confirmation", false)
                    )
                }
                reply = when {
                    relativeMinutes != null -> "فهمت التذكير: بعد $relativeMinutes دقيقة، وسأنشئ تنبيه أندرويد فعلياً."
                    day != null && time != null -> "فهمت التذكير: $day الساعة $time، وسأنشئ تنبيه أندرويد فعلياً."
                    isDaily && time != null -> "فهمت التذكير اليومي الساعة $time، وسأنشئ تنبيه أندرويد فعلياً."
                    day != null -> "فهمت اليوم ($day)، ما الساعة التي تريد التنبيه فيها؟"
                    time != null -> "فهمت الوقت ($time)، بأي يوم تريد التذكير؟"
                    else -> "فهمت أنك تريد تذكيراً. بأي يوم وساعة؟"
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
            has("مباراة", "مبارة", "ماتش", "spiel") -> {
                classification = if (has("لازم", "ضروري", "نطلع", "نروح")) "task" else "note"
                addLabel("مباراة")
                if (tomorrow) addLabel("غداً")
                if (time != null) addLabel(time)
                val opponent = Regex("(?:ضد|gegen|vs\\.?)[\\s:]+([^،,.\\n]+)", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.getOrNull(1)?.trim()?.take(60)
                if (!opponent.isNullOrBlank()) addKeyword(opponent)
                reply = buildString {
                    append("فهمت أنها مباراة")
                    if (tomorrow) append(" غداً")
                    if (time != null) append(" الساعة $time")
                    if (!opponent.isNullOrBlank()) append(" ضد $opponent")
                    append(". حفظت التفاصيل للبحث والمتابعة.")
                    if (has("قبل بساعة", "قبل ساعة")) append(" وسجلت أيضاً أنك تريد الخروج قبلها بساعة.")
                }
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
