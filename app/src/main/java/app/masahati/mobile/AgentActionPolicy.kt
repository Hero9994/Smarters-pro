package app.masahati.mobile

/** A model proposal is not permission to mutate the user's data. */
object AgentActionPolicy {
    fun allows(type: String, text: String, reminderResolved: Boolean = false): Boolean {
        val q = text.trim().replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        if (type == "create_reminder") return reminderResolved
        val description = Regex("^(?:هاي|هذه|هي|هاد|هذا)\\s+(?:ورقة|الورقة|مستند|المستند|عقد|العقد|وثيقة|الوثيقة)\\s", RegexOption.IGNORE_CASE)
        val correction = Regex("^(?:لا\\s+)?(?:مو|مش|ليس)\\s+(?:جدول\\s+)?دوام", RegexOption.IGNORE_CASE)
        if (type == "enrich_previous_document") return !q.contains('؟') && !q.contains('?') &&
            (description.containsMatchIn(q) || (correction.containsMatchIn(q) && Regex("ورقة|مستند|وثيقة|تصريح").containsMatchIn(q)))
        if (Regex("^(?:لا\\s|مو\\s|مش\\s|ما\\s|لازم\\s|don't\\b|do not\\b|nicht\\b)", RegexOption.IGNORE_CASE).containsMatchIn(q)) return false
        val pattern = when (type) {
            "search" -> "^(?:ابحث|دور|فتش|وين|أين|اين|find\\b|search\\b|suche\\b|wo ist\\b)"
            "create_space" -> "^(?:اعمل|أعمل|انشئ|أنشئ|سوي|create)\\s*(?:لي)?\\s*(?:مساحة|space)"
            "archive_space" -> "^(?:أرشف|ارشف|archive)\\s"
            "pin_space" -> "^(?:ثبت|pin)\\s"
            "rename_space" -> "^(?:غير\\s+اسم|سمي|rename)\\s+(?:هالمساحة|هذه\\s+المساحة|المساحة)"
            "rename_last_document" -> "^(?:غير\\s+اسم|سمي|rename)\\s+(?:آخر|اخر)\\s+(?:ورقة|مستند|ملف)"
            "move_last_document" -> "^(?:انقل|نقل|حرك|move)\\s+(?:آخر|اخر)\\s+(?:ورقة|مستند|ملف)"
            "move_last_item" -> "^(?:انقل|نقل|حرك|move)\\s+(?:آخر|اخر)\\s+(?:شي|شيء|عنصر|ملاحظة)"
            else -> return false
        }
        return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(q)
    }
}
