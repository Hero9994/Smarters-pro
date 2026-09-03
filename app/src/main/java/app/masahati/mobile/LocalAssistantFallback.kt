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
        fun tailAfter(vararg markers: String): String {
            for (marker in markers) {
                val idx = lower.lastIndexOf(marker.lowercase())
                if (idx >= 0) {
                    val candidate = raw.substring(idx + marker.length).trim().trim('«', '»', '"', '\'', ':', '-', ' ')
                    if (candidate.isNotBlank()) return candidate.take(120)
                }
            }
            return ""
        }

        val time = Regex("(?:[01]?\\d|2[0-3])[:.]\\d{2}").find(raw)?.value?.replace('.', ':')
            ?: Regex("(?:[01]?\\d|2[0-3])\\s*(?:ص|م)").find(raw)?.value
            ?: Regex("(?:[01]?\\d|2[0-3])[:.]\\d{2}").find(context)?.value?.replace('.', ':')
            ?: Regex("(?:[01]?\\d|2[0-3])\\s*(?:ص|م)").find(context)?.value
        val days = listOf(
            "الاثنين", "الإثنين", "اثنين", "الثلاثاء", "ثلاثاء", "الأربعاء", "الاربعاء", "أربعاء", "اربعاء",
            "الخميس", "خميس", "الجمعة", "جمعة", "السبت", "سبت", "الأحد", "الاحد", "أحد", "احد",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag"
        )
        val day = days.firstOrNull { lower.contains(it) } ?: days.firstOrNull { context.contains(it) }
        if (time != null) addKeyword(time)
        if (day != null) addKeyword(day)

        val classification: String
        val reply: String
        when {
            previousFile != null && has(
                "هي ورقة", "هاي ورقة", "هاي الورقة", "هاد المستند", "هذا المستند", "هذه الورقة", "الورقة هي", "الوثيقة هي",
                "هاد الملف", "هالـ pdf", "هال pdf", "هاي الصورة", "المسح يلي قبل", "الملف يلي قبل", "الورقة يلي قبل"
            ) -> {
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
            has("وين", "أين", "اين", "ابحث", "دور", "فتش", "find ", "search", "suche", "wo ist") -> {
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
            has("ثبت المساحة", "ثبّت المساحة", "تثبيت المساحة", "ثبت هالمساحة", "ثبّت هالمساحة", "ثبت المساحة الحالية", "ثبّت المساحة الحالية") -> {
                classification = "command"
                actions.put(JSONObject().put("type", "pin_space").put("args", JSONObject().put("space_name", spaceTitle)).put("requires_confirmation", false))
                reply = "فهمت أنك تريد تثبيت هذه المساحة."
                addLabel("تثبيت")
            }
            has("أنشئ مساحة", "انشئ مساحة", "اعمل مساحة جديدة", "create space") -> {
                classification = "command"
                var name = tailAfter("اسمها", "باسم", "create space")
                if (name.isBlank()) {
                    name = Regex("(?:أنشئ|انشئ|اعمل)\\s+مساحة(?:\\s+جديدة)?\\s+(?:لـ|ل)?(.+)$", RegexOption.IGNORE_CASE)
                        .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                }
                if (name.startsWith("ل") && name.length > 2) name = name.drop(1).trim()
                actions.put(JSONObject().put("type", "create_space").put("args", JSONObject().put("name", name.ifBlank { "مساحة جديدة" })).put("requires_confirmation", false))
                reply = if (name.isBlank()) "فهمت أنك تريد إنشاء مساحة جديدة." else "فهمت أنك تريد مساحة جديدة باسم «$name»."
                addLabel("مساحة")
            }
            has("غير اسم المساحة", "غيّر اسم المساحة", "سمي هالمساحة", "سمّي هالمساحة", "أعد تسمية المساحة", "اعد تسمية المساحة", "rename space", "benenne den bereich") -> {
                classification = "command"
                val name = tailAfter("إلى", "الى", " to ", " in ").removeSuffix(" um").trim()
                actions.put(JSONObject().put("type", "rename_space").put("args", JSONObject().put("new_name", name)).put("requires_confirmation", false))
                reply = if (name.isBlank()) "فهمت أنك تريد إعادة تسمية المساحة، لكن أحتاج الاسم الجديد." else "فهمت الاسم الجديد للمساحة: «$name»."
                addLabel("إعادة تسمية")
            }
            has("انقل المستند الأخير", "انقل الورقة الأخيرة", "حرك الملف الأخير", "move last document") -> {
                classification = "command"
                val target = tailAfter("إلى", "الى", " to ")
                actions.put(JSONObject().put("type", "move_last_document").put("args", JSONObject().put("target_space", target)).put("requires_confirmation", false))
                reply = if (target.isBlank()) "فهمت طلب نقل المستند، لكن أحتاج اسم المساحة الهدف." else "فهمت أنك تريد نقل المستند الأخير إلى «$target»."
                addLabel("نقل مستند")
            }
            has("سمي المستند الأخير", "سمّي المستند الأخير", "غير اسم الملف الأخير", "غيّر اسم الملف الأخير", "غير اسم الورقة الأخيرة", "غيّر اسم الورقة الأخيرة") -> {
                classification = "command"
                val name = tailAfter("إلى", "الى", "الأخير", "الأخيرة")
                actions.put(JSONObject().put("type", "rename_last_document").put("args", JSONObject().put("new_name", name)).put("requires_confirmation", false))
                reply = if (name.isBlank()) "فهمت طلب إعادة تسمية المستند، لكن أحتاج الاسم الجديد." else "فهمت الاسم الجديد للمستند: «$name»."
                addLabel("تسمية مستند")
            }
            has("انقل آخر شي", "انقل آخر شيء", "انقل آخر ملاحظة", "حرك آخر شيء", "حرك آخر شي") -> {
                classification = "command"
                val target = tailAfter("إلى", "الى")
                actions.put(JSONObject().put("type", "move_last_item").put("args", JSONObject().put("target_space", target)).put("requires_confirmation", false))
                reply = if (target.isBlank()) "فهمت طلب النقل، لكن أحتاج اسم المساحة الهدف." else "فهمت أنك تريد نقل آخر عنصر إلى «$target»."
                addLabel("نقل")
            }
            has("ذكرني", "ذكّرني", "تذكير", "remind", "erinner") -> {
                classification = "reminder"
                addLabel("تذكير")
                if (day != null) addLabel(day)
                val relative = Regex("بعد\\s+\\d+\\s*(?:دقيقة|دقائق|دقايق|ساعة|ساعات)", RegexOption.IGNORE_CASE).containsMatchIn(raw)
                val today = has("اليوم", "today", "heute")
                val tomorrow = has("بكرا", "غداً", "غدا", "tomorrow", "morgen")
                val daily = has("كل يوم", "يومياً", "يوميا", "daily")
                val passedToday = if (today && time != null && time.contains(':')) {
                    val parts = time.split(':')
                    val h = parts.getOrNull(0)?.toIntOrNull()
                    val m = parts.getOrNull(1)?.take(2)?.toIntOrNull()
                    if (h != null && m != null) !java.time.LocalTime.of(h, m).isAfter(java.time.LocalTime.now()) else false
                } else false

                if (passedToday) {
                    reply = "الوقت $time اليوم مرّ بالفعل. هل تقصد غداً بنفس الوقت أم وقتاً آخر؟"
                } else {
                    val reminderArgs = JSONObject().apply {
                        if (day != null) {
                            put("day_of_week", day)
                            put("repeat", "weekly")
                        }
                        if (time != null) put("time", time)
                        if (tomorrow && time != null && time.contains(':')) {
                            val parts = time.split(':')
                            val h = parts.getOrNull(0)?.toIntOrNull()
                            val m = parts.getOrNull(1)?.take(2)?.toIntOrNull()
                            if (h != null && m != null) {
                                put("trigger_at", java.time.ZonedDateTime.now().plusDays(1).withHour(h).withMinute(m).withSecond(0).withNano(0).toString())
                            }
                        }
                        if (daily) put("repeat", "daily")
                        put("title", "تذكير مساحاتي")
                        put("body", raw.take(500))
                    }
                    val enough = relative || (time != null && (day != null || today || tomorrow || daily))
                    if (enough) {
                        actions.put(JSONObject().put("type", "create_reminder").put("args", reminderArgs).put("requires_confirmation", false))
                    }
                    reply = when {
                        relative -> "فهمت التذكير النسبي وسأحوّله إلى تنبيه أندرويد فعلي."
                        day != null && time != null -> "فهمت التذكير: $day الساعة $time."
                        today && time != null -> "فهمت التذكير لليوم الساعة $time."
                        tomorrow && time != null -> "فهمت التذكير لبكرا الساعة $time."
                        daily && time != null -> "فهمت التذكير اليومي الساعة $time."
                        day != null -> "فهمت أن التذكير مرتبط بـ$day، لكن أحتاج الساعة."
                        time != null -> "فهمت الساعة $time، لكن أحتاج اليوم أو التاريخ."
                        else -> "أحتاج اليوم والوقت حتى أنشئ التذكير."
                    }
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

        when {
            has("طبيب", "دكتور", "مشفى", "مستشفى", "arzt", "krankenhaus", "doctor", "hospital") -> addLabel("صحة")
        }
        if (has("مدرسة", "school", "schule", "صف", "رحلة مدرسية")) addLabel("مدرسة")
        if (has("كرة", "تدريب", "مباراة", "football", "fußball", "fussball", "dfb")) addLabel("كرة قدم")
        if (has("سيارة", "auto", "fahrzeug", "kfz")) addLabel("سيارة")
        if (has("تأمين", "versicherung", "insurance")) addLabel("تأمين")
        if (has("بلدية", "bürgeramt", "behörde", "amt")) addLabel("إدارة")
        if (has("إيجار", "ايجار", "miete", "wohnung")) addLabel("سكن")
        if (has("ابني", "ابنتي", "زوجتي", "العائلة", "familie", "family")) addLabel("عائلة")
        if (has("موعد", "termin", "appointment")) addLabel("موعد")

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
