package app.masahati.mobile.ai

object MasahatiLocalPrompt {
    private const val RECENT_BUDGET = 1_100

    fun build(request: MasahatiAiRequest): String {
        var recentBudget = RECENT_BUDGET
        val recentBlocks = mutableListOf<String>()
        request.recent.asReversed().take(8).forEach { row ->
            if (recentBudget <= 100) return@forEach
            val block = buildString {
                append(if (row.role == "assistant") "A" else "U")
                append(" kind=").append(row.kind)
                row.displayName?.takeIf { it.isNotBlank() }?.let { append(" name=").append(it.take(90)) }
                row.summary?.takeIf { it.isNotBlank() }?.let { append("\nsummary:").append(it.take(180)) }
                row.text.takeIf { it.isNotBlank() }?.let { append("\ntext:").append(it.take(260)) }
                if (row.kind == "file") {
                    row.ocrText?.takeIf { it.isNotBlank() }?.let { append("\nocr:").append(compact(it, 260)) }
                }
            }
            val clipped = block.take(recentBudget)
            recentBudget -= clipped.length
            recentBlocks.add(0, clipped)
        }

        val doc = request.focusedDocument
        val focused = if (doc == null) "NONE" else buildString {
            append("name=").append(doc.displayName.orEmpty().take(140))
            append("\nclass=").append(doc.classification.orEmpty().take(50))
            append("\ntags=").append(doc.tags.orEmpty().take(160))
            append("\nsummary=").append(doc.summary.orEmpty().take(400))
            append("\nocr=").append(compact(doc.ocrText.orEmpty(), 1_200))
        }
        val spaces = request.availableSpaces
            .map { it.trim().take(80) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(30)
            .joinToString(" | ")

        return """
/no_think
أنت وكيل «مساحاتي» الشخصي. افهم العربية الشامية والألمانية والسياق، ثم ساعد فعلياً بدل الرد العام.
أعد JSON واحداً فقط:
{"reply":"جواب محدد","classification":"document|search|reminder|work_schedule|task|idea|note|command|other","labels":[],"keywords":[],"summary":"ملخص","confidence":0.0,"actions":[]}

قواعد:
- المستند الحالي هو المرجع الأول لـ: هاد/هاي/الورقة/فيها/شو سميتها. استخدم name/summary/ocr فقط ولا تخترع.
- استخدم المحادثة السابقة لحل «فيها، نفس الموعد، يلي قبل».
- إذا تعرف الجواب من السياق جاوب مباشرة وبالتفصيل اللازم، لا تقل فقط «حفظتها».
- إذا غير واثق اسأل سؤالاً واحداً موجهاً.
- لا تدّعي أن إجراء تم؛ التطبيق يؤكد التنفيذ بعد نجاحه.
- استخرج وسوماً وكلمات بحث مفيدة من الأسماء والجهات والتواريخ.

أدوات actions المسموحة:
search {"query":"..."}؛ create_space {"name":"..."}؛ archive_space {"space_name":"..."}؛ pin_space {"space_name":"..."}؛
rename_space {"new_name":"..."}؛ move_last_item {"target_space":"..."}؛ rename_last_document {"new_name":"..."}؛
move_last_document {"target_space":"..."}؛ enrich_previous_document {"summary":"...","labels":[],"keywords":[]}.
ضع requires_confirmation=false فقط عندما أمر المستخدم بالإجراء بشكل صريح. الحذف غير متاح.
إذا شرح المستخدم المستند السابق مثل «هي ورقة تسمح بالنقل من المنزل للطبيب» استخدم enrich_previous_document ولا تعامل الشرح كملاحظة منفصلة.
عند النقل استخدم اسماً موجوداً في SPACES.

space=${request.spaceTitle.take(100)}
now=${request.nowIso.take(70)}
timezone=${request.timezone.take(50)}
SPACES=$spaces

CURRENT_DOCUMENT:
$focused

RECENT:
${recentBlocks.joinToString("\n\n")}

USER:
${request.userText.take(800)}
""".trimIndent()
    }

    internal fun compact(value: String, maxChars: Int): String {
        val clean = value.trim()
        if (clean.length <= maxChars) return clean
        val head = maxChars * 3 / 5
        val tail = maxChars - head
        return clean.take(head) + "\n[…middle omitted…]\n" + clean.takeLast(tail)
    }
}
