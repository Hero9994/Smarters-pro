package app.masahati.mobile

import org.junit.Assert.*
import org.junit.Test

class AgentActionPolicyTest {
    @Test fun normalQuestionsAndCorrectionsDoNotAuthorizeMutations() {
        for (type in listOf("create_space", "move_last_document", "archive_space", "enrich_previous_document")) {
            for (text in listOf("كم كتاب بقي؟", "نقلت المفاتيح للكيس الأخضر", "لا تنقل آخر ملف", "هل تستطيع إنشاء مساحة؟")) {
                assertFalse("$type: $text", AgentActionPolicy.allows(type, text))
            }
        }
        assertFalse(AgentActionPolicy.allows("create_reminder", "موعدي بكرا", false))
        assertTrue(AgentActionPolicy.allows("create_reminder", "الساعة خمسة", true))
    }

    @Test fun permitsOnlyTheRequestedAction() {
        assertTrue(AgentActionPolicy.allows("create_space", "اعمل مساحة باسم الدراسة"))
        assertFalse(AgentActionPolicy.allows("move_last_document", "اعمل مساحة باسم الدراسة"))
        assertTrue(AgentActionPolicy.allows("rename_last_document", "غيّر اسم آخر ملف إلى عقد البيت"))
        assertTrue(AgentActionPolicy.allows("enrich_previous_document", "هاي ورقة تسمح بالنقل إلى الطبيب"))
    }
}
