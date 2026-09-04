package app.masahati.mobile.ai

object MasahatiLocalPrompt {
    fun build(request: MasahatiAiRequest): String {
        val doc = request.focusedDocument
        val recentText = request.recent.takeLast(14).joinToString("\n\n") { row ->
            buildString {
                append(if (row.role == "assistant") "ASSISTANT" else "USER")
                append(" | kind=").append(row.kind)
                row.displayName?.takeIf { it.isNotBlank() }?.let { append(" | name=").append(it.take(180)) }
                row.classification?.takeIf { it.isNotBlank() }?.let { append(" | class=").append(it.take(80)) }
                row.tags?.takeIf { it.isNotBlank() }?.let { append(" | tags=").append(it.take(240)) }
                row.summary?.takeIf { it.isNotBlank() }?.let { append("\nsummary: ").append(it.take(600)) }
                row.text.takeIf { it.isNotBlank() }?.let { append("\ntext: ").append(it.take(1000)) }
                row.ocrText?.takeIf { it.isNotBlank() }?.let { append("\nocr: ").append(it.take(2600)) }
            }
        }

        val focused = if (doc == null) {
            "NONE"
        } else {
            buildString {
                append("id=").append(doc.id)
                append("\nname=").append(doc.displayName.orEmpty())
                append("\nclassification=").append(doc.classification.orEmpty())
                append("\ntags=").append(doc.tags.orEmpty())
                append("\nsummary=").append(doc.summary.orEmpty().take(1000))
                append("\nocr=").append(doc.ocrText.orEmpty().take(5000))
            }
        }

        return """
/no_think
أنت مساعد تطبيق "مساحاتي" الشخصي. المستخدم يتحدث غالباً بالعربية العامية السورية/الشامية، وقد تكون المستندات بالألمانية.
مهمتك فهم السياق والمستند الحالي بدقة، وليس إعطاء رد عام.

قواعد صارمة:
1) CURRENT_FOCUSED_DOCUMENT هو المرجع الأول لعبارات: هاد، هاي، الورقة، فيها، شو سميتها، شو محتواها، يلي قبلها.
2) إذا سأل المستخدم عن اسم الورقة فأجب من name حرفياً.
3) إذا سأل عن محتواها فاستعمل summary وOCR ولا تخترع.
4) إذا كانت المعلومة غير موجودة قل بوضوح إنك لا تراها.
5) لا تدّعي تنفيذ تذكير/نقل/حذف/بحث. التنفيذ يتم من Android فقط.
6) أعد JSON واحد فقط، بلا Markdown وبلا شرح خارج JSON.

صيغة JSON:
{
  "reply":"جواب مباشر للمستخدم",
  "classification":"document|search|reminder|work_schedule|task|idea|note|command|other",
  "labels":["..."],
  "keywords":["..."],
  "summary":"ملخص قصير",
  "confidence":0.0,
  "actions":[]
}

space=${request.spaceTitle}
now=${request.nowIso}
timezone=${request.timezone}

CURRENT_FOCUSED_DOCUMENT:
${focused}

RECENT_CONTEXT:
${recentText}

USER:
${request.userText.take(6000)}
""".trimIndent()
    }
}
