package app.masahati.mobile

import org.json.JSONArray
import org.json.JSONObject

object LocalAssistantFallback {
    fun analyze(text: String, spaceTitle: String, recent: List<MessageRow> = emptyList()): JSONObject {
        val raw = text.trim()
        val lower = raw.lowercase()
        val context = recent.takeLast(8).joinToString(" ") { it.text.ifBlank { it.ocrText.orEmpty() } }.lowercase()
        val lastUserContext = recent.asReversed()
            .firstOrNull { it.role == "user" }
            ?.let { it.text.ifBlank { it.ocrText.orEmpty() } }
            .orEmpty()

        val labels = linkedSetOf<String>()
        val keywords = linkedSetOf<String>()
        val actions = JSONArray()

        fun has(vararg words: String) = words.any { lower.contains(it) }
        fun addLabel(value: String) { if (value.isNotBlank()) labels += value }
        fun addKeyword(value: String) { if (value.isNotBlank()) keywords += value }

        val time = Regex("(?:[01]?\\d|2[0-3])[:.]\\d{2}").find(raw)?.value?.replace('.', ':')
            ?: Regex("(?:[01]?\\d|2[0-3])[:.]\\d{2}").find(context)?.value?.replace('.', ':')

        val days = listOf(
            "الاثنين", "الإثنين", "اثنين",
            "الثلاثاء", "ثلاثاء",
            "الأربعاء", "الاربعاء", "أربعاء", "اربعاء",
            "الخميس", "خميس",
            "الجمعة", "جمعة",
            "السبت", "سبت",
            "الأحد", "الاحد", "أحد", "احد"
        )
        val day = days.firstOrNull { lower.contains(it) } ?: days.firstOrNull { context.contains(it) }
        if (time != null) addKeyword(time)
        if (day != null) addKeyword(day)

        val recentFile = recent.asReversed().firstOrNull { it.kind == "file" && it.role == "user" }
        val documentClarification = recentFile != null && (
            Regex("^(هي|هو|هاي|هاد|هيدا|هيدي|هذا|هذه)\\s+").containsMatchIn(lower) ||
                has("الورقة", "المستند", "الوثيقة", "الملف السابق", "قصدي", "تصحيح")
            )

        val classification: String
        val reply: String

        when {
            documentClarification -> {
                classification = "document"
                addLabel("مستند")
                val description = raw
                    .replace(Regex("^(هي|هو|هاي|هاد|هيدا|هيدي|هذا|هذه)\\s+"), "")
                    .trim()
                    .ifBlank { raw }
                val detailKeywords = description
                    .split(Regex("[^\\p{L}\\p{N}]+"))
                    .map { it.trim() }
                    .filter { it.length >= 3 }
                    .distinct()
                    .take(10)
                detailKeywords.forEach(::addKeyword)
                actions.put(
                    JSONObject()
                        .put("type", "enrich_previous_document")
                        .put(
                            "args",
                            JSONObject()
                                .put("summary", description.take(320))
                                .put("labels", JSONArray(listOf("مستند")))
                                .put("keywords", JSONArray(detailKeywords))
                        )
                        .put("requires_confirmation", false)
                )
                reply = "فهمت. سأصحح وصف المستند السابق إلى: ${description.take(220)}"
            }

            has("وين", "أين", "اين", "ابحث", "دور", "فتش", "find ", "suche", "wo ist") -> {
                classification = "search"
                val q = raw
                    .replace(Regex("^(وين|أين|اين|ابحث عن|دور على|فتش عن)\\s*"), "")
                    .replace(Regex("^(حطيت|حطيتلي|وضعت|حفظت|خزنت)\\s*"), "")
                    .replace(Regex("[؟?]+$"), "")
                    .trim()
                    .ifBlank { raw }
                actions.put(
                    JSONObject()
                        .put("type", "search")
                        .put("args", JSONObject().put("query", q))
                        .put("requires_confirmation", false)
                )
                reply = "سأبحث داخل مساحاتك عن «$q» وأعرض لك أقرب النتائج."
                addLabel("بحث")
                addKeyword(q.take(80))
            }

            (raw.contains("؟") || raw.contains("?") ||
                has("ضد مين", "مين الخصم", "ضد من", "متى", "امتى", "إمتى", "شو الموعد", "أي ساعة", "اي ساعة", "شو هاد", "شو هاي")) &&
                recent.isNotEmpty() -> {
                classification = "question"
                addLabel("سؤال")
                reply = when {
                    has("ضد مين", "مين الخصم", "ضد من") -> {
                        val after = lastUserContext.substringAfter("ضد ", "").trim()
                        val opponent = after
                            .split(' ', '،', ',', '.', '؟', '?')
                            .firstOrNull { it.isNotBlank() }
                        if (opponent != null) {
                            "حسب آخر معلومة عندي: المباراة ضد $opponent."
                        } else {
                            "ما لقيت اسم الخصم بشكل واضح في آخر المعلومات."
                        }
                    }

                    has("متى", "امتى", "إمتى", "شو الموعد", "أي ساعة", "اي ساعة") -> when {
                        day != null && time != null -> "حسب آخر معلومة عندي: $day الساعة $time."
                        day != null -> "حسب آخر معلومة عندي: الموعد يوم $day، لكن الوقت غير واضح."
                        time != null -> "حسب آخر معلومة عندي: الساعة $time، لكن اليوم غير واضح."
                        else -> "ما لقيت موعداً واضحاً في آخر المعلومات."
                    }

                    recentFile != null && has("شو هاد", "شو هاي", "اشو هاد", "ايش هاد") -> {
                        val d = recentFile.summary?.takeIf { it.isNotBlank() }
                            ?: recentFile.tags?.takeIf { it.isNotBlank() }
                            ?: recentFile.displayName.orEmpty()
                        if (d.isNotBlank()) "حسب آخر مستند: $d." else "عندي مستند سابق، لكن وصفه غير كافٍ."
                    }

                    else -> if (lastUserContext.isNotBlank()) {
                        "حسب آخر معلومة عندي: ${lastUserContext.take(220)}"
                    } else {
                        "ما عندي سياق كافٍ لأجاوب بدقة."
                    }
                }
            }

            has("أرشف", "ارشف", "أرشفة", "ارشفة") -> {
                classification = "command"
                actions.put(
                    JSONObject()
                        .put("type", "archive_space")
                        .put("args", JSONObject().put("space_name", spaceTitle))
                        .put("requires_confirmation", false)
                )
                reply = "فهمت أنك تريد أرشفة هذه المساحة."
                addLabel("أرشفة")
            }

            has("ثبت المساحة", "ثبّت المساحة", "تثبيت المساحة") -> {
                classification = "command"
                actions.put(
                    JSONObject()
                        .put("type", "pin_space")
                        .put("args", JSONObject().put("space_name", spaceTitle))
                        .put("requires_confirmation", false)
                )
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
                actions.put(
                    JSONObject()
                        .put("type", "create_reminder")
                        .put("args", reminderArgs)
                        .put("requires_confirmation", false)
                )
                reply = when {
                    day != null && time != null -> "فهمت التذكير: $day الساعة $time."
                    day != null -> "فهمت أن التذكير مرتبط بـ$day، لكن لا يوجد وقت واضح بعد."
                    time != null -> "فهمت وقت التذكير $time، لكن اليوم أو التاريخ غير واضح بعد."
                    else -> "فهمت أنك تريد تذكيراً، لكن أحتاج اليوم والوقت بشكل أوضح."
                }
            }

            has("دوام", "شفت", "مناوبة", "arbeit", "schicht") -> {
                classification = "work_schedule"
                addLabel("دوام")
                if (day != null) addLabel(day)
                reply = if (day != null && time != null) {
                    "فهمت جدول الدوام: $day الساعة $time."
                } else {
                    "فهمت أنها معلومة مرتبطة بالدوام وحفظتها للتصنيف والبحث."
                }
            }

            has("جواز", "عقد", "فاتورة", "وثيقة", "مستند", "ورقة", "pdf", "rechnung", "vertrag", "pass") -> {
                classification = "document"
                addLabel("مستند")
                listOf("جواز", "عقد", "فاتورة", "وثيقة").firstOrNull { lower.contains(it) }?.let(::addLabel)
                reply = "فهمت أنه مستند وسأفهرس وصفه والكلمات الأساسية حتى تقدر تلاقيه لاحقاً."
            }

            has("فكرة", "مشروع", "idea") -> {
                classification = "idea"
                addLabel("فكرة")
                reply = "فهمت الفكرة وصنفتها حتى يسهل الرجوع إليها."
            }

            has("لازم", "مهمة", "اعمل", "أعمل", "task", "todo") -> {
                classification = "task"
                addLabel("مهمة")
                reply = "فهمت أنها مهمة وصنفتها بشكل منفصل."
            }

            else -> {
                classification = "note"
                addLabel("ملاحظة")
                reply = "فهمت: ${raw.take(220)}"
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
            .put("confidence", 0.68)
            .put("actions", actions)
    }
}
