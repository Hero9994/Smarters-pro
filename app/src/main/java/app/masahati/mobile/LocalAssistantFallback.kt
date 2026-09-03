package app.masahati.mobile

import org.json.JSONArray
import org.json.JSONObject

object LocalAssistantFallback {
    private val diacritics = Regex("[\\u064B-\\u065F\\u0670]")
    private val stopWords = setOf("هذا","هذه","هاد","هاي","هالشي","اللي","يلي","على","الى","إلى","من","في","عن","مع","انا","أنا","بدي","بدّي","اريد","أريد","وين","أين","اين","حطيت","حفظت","وضعت","خزنت","دور","ابحث","فتش","شو","اشو","ايش","the","and","und","der","die","das","wo","suche")

    fun analyze(text: String, spaceTitle: String, recent: List<MessageRow> = emptyList()): JSONObject {
        val raw = text.trim()
        val norm = normalize(raw)
        val recentTexts = recent.takeLast(8).map { it.text.ifBlank { it.ocrText.orEmpty() }.trim() }.filter { it.isNotBlank() }
        val contextRaw = recentTexts.joinToString(" ")
        val labels = linkedSetOf<String>()
        val keywords = linkedSetOf<String>()
        val actions = JSONArray()

        fun has(vararg words: String) = words.any { norm.contains(normalize(it)) }
        fun addLabel(v: String) { if (v.isNotBlank()) labels += v }
        fun addKeyword(v: String) { if (v.trim().length >= 2) keywords += v.trim() }

        val time = extractTime(raw) ?: extractTime(contextRaw)
        val day = extractWeekday(raw) ?: extractWeekday(contextRaw)
        val date = extractDate(raw) ?: extractDate(contextRaw)
        val recurrence = extractRecurrence(raw, day)
        listOfNotNull(time, day, date, recurrence).forEach(::addKeyword)

        val docLabels = detectDocumentLabels("$raw $contextRaw")
        val looksLikeDocument = has("مستند ممسوح", "ملف مرفق", "النص المستخرج", "pdf", "scan-", "ورقة", "وثيقة", "مستند", "جواز", "هوية", "عقد", "فاتورة", "تقرير", "شهادة", "تصريح", "موافقة", "bescheinigung", "bescheid", "rechnung", "vertrag", "reisepass", "ausweis", "genehmigung") || docLabels.isNotEmpty()

        val classification: String
        val reply: String
        when {
            isSearch(norm) -> {
                classification = "search"
                addLabel("بحث")
                val q = cleanSearchQuery(raw).ifBlank { recentTexts.lastOrNull()?.let(::cleanSearchQuery).orEmpty().ifBlank { raw } }
                q.splitWords().take(8).forEach(::addKeyword)
                actions.put(action("search", JSONObject().put("query", q), false))
                reply = "سأبحث محلياً عن «$q» داخل النصوص وأسماء الملفات وOCR والتصنيفات."
            }
            isArchive(norm) -> {
                classification = "command"
                addLabel("أرشفة")
                actions.put(action("archive_space", JSONObject().put("space_name", spaceTitle), false))
                reply = "فهمت الأمر: أرشفة مساحة «$spaceTitle»."
            }
            isPin(norm) -> {
                classification = "command"
                addLabel("تثبيت")
                actions.put(action("pin_space", JSONObject().put("space_name", spaceTitle), false))
                reply = "فهمت الأمر: تثبيت مساحة «$spaceTitle»."
            }
            parseRename(raw) != null -> {
                classification = "command"
                val newName = parseRename(raw)!!.take(120)
                addLabel("إعادة تسمية"); addKeyword(newName)
                actions.put(action("rename_space", JSONObject().put("new_name", newName), false))
                reply = "سأغيّر اسم هذه المساحة إلى «$newName»."
            }
            parseMoveTarget(raw) != null -> {
                classification = "command"
                val target = parseMoveTarget(raw)!!.take(120)
                addLabel("نقل"); addKeyword(target)
                actions.put(action("move_last_item", JSONObject().put("target_space", target), false))
                reply = "فهمت أنك تريد نقل آخر عنصر إلى مساحة «$target»."
            }
            isReminder(norm) -> {
                classification = "reminder"
                addLabel("تذكير"); day?.let(::addLabel); recurrence?.let(::addLabel)
                reply = when {
                    day != null && time != null && recurrence != null -> "فهمت التذكير المتكرر: $recurrence الساعة $time. حفظت اليوم والوقت بشكل منظم."
                    day != null && time != null -> "فهمت التذكير: $day الساعة $time."
                    date != null && time != null -> "فهمت التذكير بتاريخ $date الساعة $time."
                    day != null -> "فهمت أن التذكير مرتبط بـ$day، لكن الوقت غير واضح."
                    time != null -> "فهمت وقت التذكير $time، لكن اليوم أو التاريخ غير واضح."
                    else -> "فهمت أنك تريد تذكيراً، لكن اليوم والوقت غير واضحين بعد."
                }
            }
            isSchedule(norm) -> {
                classification = "work_schedule"
                addLabel("دوام"); day?.let(::addLabel); recurrence?.let(::addLabel)
                reply = when {
                    day != null && time != null && recurrence != null -> "فهمت جدول الدوام: $recurrence الساعة $time. سأفهرسه تحت الدوام واليوم والوقت."
                    day != null && time != null -> "فهمت جدول الدوام: $day الساعة $time."
                    else -> "فهمت أنها معلومة مرتبطة بالدوام واستخرجت الكلمات المفيدة للبحث."
                }
            }
            looksLikeDocument -> {
                classification = "document"
                addLabel("مستند")
                docLabels.forEach(::addLabel); docLabels.forEach(::addKeyword)
                date?.let(::addKeyword)
                reply = buildDocumentReply(raw, docLabels)
            }
            has("فكرة", "مشروع", "idea", "اقتراح") -> {
                classification = "idea"; addLabel("فكرة"); topicTokens(raw).forEach(::addKeyword)
                reply = "فهمت أنها فكرة${topicSuffix(raw)}. صنفتها وربطتها بالكلمات المهمة للبحث."
            }
            has("لازم", "مهمة", "اعمل", "أعمل", "مطلوب", "task", "todo", "erledigen") -> {
                classification = "task"; addLabel("مهمة"); topicTokens(raw).forEach(::addKeyword)
                reply = "فهمت أنها مهمة${topicSuffix(raw)}. فصلتها عن الملاحظات العادية."
            }
            else -> {
                classification = "note"; addLabel("ملاحظة")
                val contextual = detectGeneralLabels("$raw $contextRaw")
                contextual.forEach(::addLabel); contextual.forEach(::addKeyword); topicTokens(raw).forEach(::addKeyword)
                reply = if (contextual.isNotEmpty()) "فهمت الملاحظة وربطتها بـ${contextual.take(3).joinToString("، ")} حتى يسهل العثور عليها." else "حفظت «${raw.take(110)}» كملاحظة وفهرست كلماتها المهمة للبحث."
            }
        }

        topicTokens(raw).forEach(::addKeyword)
        val confidence = when (classification) {
            "command", "search" -> 0.92
            "reminder", "work_schedule" -> if (time != null || day != null || date != null) 0.88 else 0.72
            "document" -> if (docLabels.isNotEmpty()) 0.90 else 0.76
            "task", "idea" -> 0.82
            else -> 0.72
        }
        val summary = buildSummary(raw, classification, docLabels, day, time, date, recurrence)
        return JSONObject().put("ok", true).put("engine", "local-intelligence-v2").put("reply", reply).put("classification", classification).put("labels", JSONArray(labels.toList().take(10))).put("keywords", JSONArray(keywords.toList().take(18))).put("summary", summary.take(420)).put("confidence", confidence).put("actions", actions)
    }

    private fun normalize(v: String) = v.lowercase().replace(diacritics, "").replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي').replace('ؤ','و').replace('ئ','ي').replace(Regex("\\s+"), " ").trim()
    private fun String.splitWords(): List<String> = normalize(this).split(Regex("[^\\p{L}\\p{N}:./-]+")).map { it.trim() }.filter { it.length >= 2 && it !in stopWords }.distinct()
    private fun topicTokens(raw: String) = raw.splitWords().filterNot { it.matches(Regex("\\d+")) }.take(10)
    private fun topicSuffix(raw: String): String { val t = topicTokens(raw).take(4); return if (t.isEmpty()) "" else " عن ${t.joinToString(" ")}" }

    private fun extractTime(value: String): String? {
        val n = normalize(value)
        Regex("\\b(?:[01]?\\d|2[0-3])[:.]\\d{2}\\b").find(n)?.value?.let { return it.replace('.', ':') }
        Regex("\\b(1[0-2]|0?[1-9])\\s*(?:و\\s*)?(نص|نصف|ونص|ونصف)\\b").find(n)?.let { var h=it.groupValues[1].toInt(); if(isPm(n,it.range.last)&&h<12)h+=12; return "%02d:30".format(h) }
        Regex("\\b(1[0-2]|0?[1-9])\\s*(?:و\\s*)?(ربع|وربع)\\b").find(n)?.let { var h=it.groupValues[1].toInt(); if(isPm(n,it.range.last)&&h<12)h+=12; return "%02d:15".format(h) }
        Regex("(?:الساعه|الساعة|um)\\s*(1[0-2]|0?[1-9]|2[0-3])\\b").find(n)?.let { var h=it.groupValues[1].toInt(); if(isPm(n,it.range.last)&&h<12)h+=12; return "%02d:00".format(h) }
        return null
    }
    private fun isPm(text:String,pos:Int):Boolean { val tail=text.substring(pos.coerceAtMost(text.length)).take(20); return listOf("مساء","المسا","بالليل","pm").any{tail.contains(it)} }
    private fun extractWeekday(value:String):String? { val n=normalize(value); val m=linkedMapOf("الاثنين" to listOf("الاثنين","اثنين","اتنين","montag","monday"),"الثلاثاء" to listOf("الثلاثاء","ثلاثاء","تلاتا","dienstag","tuesday"),"الأربعاء" to listOf("الاربعاء","اربعاء","اربعا","mittwoch","wednesday"),"الخميس" to listOf("الخميس","خميس","donnerstag","thursday"),"الجمعة" to listOf("الجمعه","الجمعة","جمعة","freitag","friday"),"السبت" to listOf("السبت","سبت","samstag","saturday"),"الأحد" to listOf("الاحد","احد","sonntag","sunday")); return m.entries.firstOrNull{e->e.value.any{n.contains(normalize(it))}}?.key }
    private fun extractDate(value:String):String? { Regex("\\b([0-3]?\\d)[./-]([01]?\\d)(?:[./-](20\\d{2}|\\d{2}))?\\b").find(value)?.let { val d=it.groupValues[1].padStart(2,'0'); val m=it.groupValues[2].padStart(2,'0'); val y=it.groupValues[3]; return if(y.isBlank()) "$d.$m." else "$d.$m.${if(y.length==2)"20$y" else y}" }; val n=normalize(value); return when { n.contains("بكره")||n.contains("غدا")||n.contains("morgen") -> "غداً"; n.contains("اليوم")||n.contains("heute") -> "اليوم"; else -> null } }
    private fun extractRecurrence(value:String,day:String?):String? { val n=normalize(value); return when { day!=null&&(n.contains(normalize("كل $day"))||n.contains("jeden")||n.contains("every"))->"كل $day"; n.contains("كل يوم")||n.contains("يوميا")||n.contains("taglich")||n.contains("täglich")->"كل يوم"; n.contains("كل اسبوع")||n.contains("اسبوعيا")||n.contains("wochentlich")||n.contains("wöchentlich")->"كل أسبوع"; else->null } }
    private fun isSearch(n:String)=listOf("وين","اين","ابحث","دور","فتش","لقيل","find ","search","suche","wo ist","wo habe").any{n.contains(normalize(it))}
    private fun isArchive(n:String)=listOf("ارشف","ارشفة","archiv").any{n.contains(normalize(it))}
    private fun isPin(n:String)=listOf("ثبت المساحة","تثبيت المساحة","pin space","anheften").any{n.contains(normalize(it))}
    private fun isReminder(n:String)=listOf("ذكرني","تذكير","remind","erinner").any{n.contains(normalize(it))}
    private fun isSchedule(n:String)=listOf("دوام","شفت","مناوبة","arbeit","schicht","dienstplan","arbeitszeit").any{n.contains(normalize(it))}

    private fun cleanSearchQuery(value:String):String { var q=normalize(value); val prefixes=listOf("وين حطيت","وين حفظت","وين وضعت","اين حطيت","اين حفظت","اين وضعت","ابحث عن","دور على","دورلي على","فتش عن","لقيل","find","search for","suche nach","wo ist","wo habe ich"); prefixes.sortedByDescending{it.length}.firstOrNull{q.startsWith(normalize(it))}?.let{q=q.removePrefix(normalize(it)).trim()}; return q.replace(Regex("[؟?]+$"),"").trim().ifBlank{value.trim()} }
    private fun parseRename(value:String):String? { val p=listOf(Regex("(?:غيّر|غير|غيّرلي|غيرلي|سمّي|سمي)\\s+(?:اسم\\s+)?(?:المساحة\\s+)?(?:إلى|الى|لـ|ل)\\s+(.+)$",RegexOption.IGNORE_CASE),Regex("(?:rename)\\s+(?:space\\s+)?(?:to\\s+)?(.+)$",RegexOption.IGNORE_CASE)); return p.firstNotNullOfOrNull{it.find(value.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)} }
    private fun parseMoveTarget(value:String):String? { val p=listOf(Regex("(?:انقل|نقل)\\s+(?:هاد|هذا|هذه|هالشي|اخر شي|آخر شي|اخر عنصر|آخر عنصر)?\\s*(?:إلى|الى|على|لـ)\\s+(.+)$",RegexOption.IGNORE_CASE),Regex("(?:move)\\s+(?:this|last item)?\\s*(?:to)\\s+(.+)$",RegexOption.IGNORE_CASE)); return p.firstNotNullOfOrNull{it.find(value.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)} }

    private fun detectDocumentLabels(value:String):List<String> { val n=normalize(value); val c=linkedMapOf("جواز سفر" to listOf("جواز","reisepass","passport"),"هوية" to listOf("هوية","ausweis","personalausweis"),"تأمين صحي" to listOf("aok","krankenkasse","krankenversicherung","versicherung","تأمين صحي","تامين صحي"),"Wohngeld" to listOf("wohngeld"),"إيجار" to listOf("miete","mietvertrag","nebenkosten","اجار","إيجار"),"فاتورة" to listOf("rechnung","invoice","فاتوره","فاتورة"),"عقد" to listOf("vertrag","contract","عقد"),"إلغاء" to listOf("kündigung","kuendigung","الغاء","إلغاء"),"مدرسة" to listOf("schule","schulbescheinigung","مدرسه","مدرسة"),"طب" to listOf("arzt","krankenhaus","patient","طبيب","مشفى","مريض"),"نقل مرضى" to listOf("krankenfahrt","beförderung","beforderung","transport","نقل من المنزل","نقل مرض","النقل من المنزل"),"تصريح/موافقة" to listOf("genehmigung","bewilligung","erlaubnis","تصريح","موافقة","تسمح"),"عمل" to listOf("arbeitsvertrag","lohnabrechnung","gehalt","arbeitgeber","عمل","راتب","دوام"),"جنسية" to listOf("einbürgerung","einbuergerung","staatsangehörigkeit","staatsangehorigkeit","جنسية"),"موعد" to listOf("termin","appointment","موعد"),"إقامة" to listOf("aufenthalt","aufenthaltstitel","اقامة","إقامة")); return c.entries.filter{e->e.value.any{n.contains(normalize(it))}}.map{it.key}.take(6) }
    private fun detectGeneralLabels(value:String):List<String> { val n=normalize(value); val c=linkedMapOf("كرة قدم" to listOf("كره قدم","كرة قدم","football","fußball","fussball","مباراة","مباريات"),"رضوان" to listOf("رضوان","radwan"),"سيارة" to listOf("سياره","سيارة","auto","fahrzeug"),"دوام" to listOf("دوام","arbeit","schicht"),"طبيب" to listOf("طبيب","arzt"),"مدرسة" to listOf("مدرسه","مدرسة","schule")); return c.entries.filter{e->e.value.any{n.contains(normalize(it))}}.map{it.key}.take(5) }
    private fun buildDocumentReply(raw:String,labels:List<String>):String { val t=labels.take(4).joinToString("، "); val n=normalize(raw); return when { labels.contains("نقل مرضى")&&labels.contains("طب")->"فهمت أنها ورقة مرتبطة بنقل المريض من المنزل إلى الطبيب. صنفتها تحت «نقل مرضى» و«طب» لتظهر عند البحث بهذه المعاني."; labels.contains("تأمين صحي")->"فهمت أن المستند متعلق بالتأمين الصحي${if(t.isNotBlank())" ($t)" else ""}. حفظت التصنيف وكلمات البحث محلياً."; labels.contains("Wohngeld")->"فهمت أن المستند متعلق بـWohngeld. حفظت هذا التصنيف مع الكلمات المهمة للبحث."; labels.contains("عقد")->"فهمت أنه مستند عقد${if(labels.contains("إلغاء"))" ويتضمن موضوع إلغاء/إنهاء" else ""}. فهرسته محلياً."; labels.contains("جواز سفر")->"فهمت أنه مستند متعلق بجواز السفر. فهرسته لتجده بسرعة."; t.isNotBlank()->"فهمت نوع المستند وصنفته تحت: $t. لم أرسل محتواه لأي خدمة خارجية."; n.contains("النص المستخرج")->"قرأت النص المستخرج من المستند محلياً وفهرسته، لكن النوع لم يكن واضحاً بما يكفي لتصنيف أدق."; else->"فهمت أنه مستند وحفظت وصفه وكلمات البحث محلياً بدون إرسال الملف للخارج." } }
    private fun buildSummary(raw:String,c:String,docs:List<String>,day:String?,time:String?,date:String?,rec:String?):String = when(c){"reminder"->listOfNotNull("تذكير",rec?:day?:date,time).joinToString(" · ");"work_schedule"->listOfNotNull("دوام",rec?:day?:date,time).joinToString(" · ");"document"->if(docs.isNotEmpty())"مستند: ${docs.joinToString("، ")}" else raw.take(320);"search"->"بحث: ${cleanSearchQuery(raw)}";else->raw.take(320)}
    private fun action(type:String,args:JSONObject,confirm:Boolean)=JSONObject().put("type",type).put("args",args).put("requires_confirmation",confirm)
}
