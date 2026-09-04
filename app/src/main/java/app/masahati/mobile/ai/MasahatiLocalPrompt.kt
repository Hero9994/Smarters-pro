package app.masahati.mobile.ai

object MasahatiLocalPrompt {
    private const val RECENT_BUDGET = 1_900

    fun build(request: MasahatiAiRequest): String {
        var recentBudget = RECENT_BUDGET
        val recentBlocks = mutableListOf<String>()
        request.recent.asReversed().take(10).forEach { row ->
            if (recentBudget <= 120) return@forEach
            val block = buildString {
                append(if (row.role == "assistant") "ASSISTANT" else "USER")
                append(" kind=").append(row.kind)
                row.displayName?.takeIf { it.isNotBlank() }?.let { append(" name=").append(it.take(120)) }
                row.summary?.takeIf { it.isNotBlank() }?.let { append("\nsummary: ").append(it.take(280)) }
                row.text.takeIf { it.isNotBlank() }?.let { append("\ntext: ").append(it.take(420)) }
                if (row.kind == "file") {
                    row.ocrText?.takeIf { it.isNotBlank() }?.let { append("\nocr: ").append(compact(it, 500)) }
                }
            }
            val clipped = block.take(recentBudget)
            recentBudget -= clipped.length
            recentBlocks.add(0, clipped)
        }
        val recentText = recentBlocks.joinToString("\n\n")

        val doc = request.focusedDocument
        val focused = if (doc == null) {
            "NONE"
        } else {
            buildString {
                append("id=").append(doc.id)
                append("\nname=").append(doc.displayName.orEmpty().take(180))
                append("\nclassification=").append(doc.classification.orEmpty().take(80))
                append("\ntags=").append(doc.tags.orEmpty().take(240))
                append("\nsummary=").append(doc.summary.orEmpty().take(700))
                append("\nocr=").append(compact(doc.ocrText.orEmpty(), 2_800))
            }
        }

        return """
/no_think
أنت مساعد "مساحاتي". المستخدم يتحدث غالباً بالعربية الشامية والمستندات قد تكون بالألمانية.
افهم السياق والمستند الحالي بدقة. لا تعطِ جواباً عاماً إذا كانت المعلومة موجودة.

قواعد:
1) CURRENT_FOCUSED_DOCUMENT هو المرجع الأول لـ: هاد، هاي، الورقة، فيها، شو سميتها، شو محتواها، يلي قبلها.
2) سؤال اسم الورقة: استخدم name حرفياً.
3) سؤال المحتوى: استخدم summary وocr فقط ولا تخترع.
4) إذا المعلومة غير موجودة قل إنك لا تراها.
5) لا تدّعِ تنفيذ أي إجراء؛ Android ينفذ الأدوات.
6) أعد JSON واحداً فقط بلا Markdown.

JSON:
{"reply":"...","classification":"document|search|reminder|work_schedule|task|idea|note|command|other","labels":[],"keywords":[],"summary":"...","confidence":0.0,"actions":[]}

space=${request.spaceTitle.take(100)}
now=${request.nowIso.take(80)}
timezone=${request.timezone.take(60)}

CURRENT_FOCUSED_DOCUMENT:
$focused

RECENT_CONTEXT:
$recentText

USER:
${request.userText.take(1_200)}
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
