package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAssistantFallbackTest {
    @Test
    fun classifiesPatientTransportDocument() {
        val result = LocalAssistantFallback.analyze("هي ورقة تسمح بالنقل من المنزل الى الطبيب", "مباريات رضوان")
        assertEquals("document", result.getString("classification"))
        val labels = result.getJSONArray("labels").toString()
        assertTrue(labels.contains("نقل مرضى"))
        assertTrue(labels.contains("طب"))
        assertTrue(result.getString("reply").contains("نقل"))
    }

    @Test
    fun resolvesReminderTimeFromConversationContext() {
        val recent = listOf(MessageRow(1, 1, "user", "text", "دوامي كل يوم اثنين الساعة 18.30", null, null, null, null, null, null, null, 1))
        val result = LocalAssistantFallback.analyze("بدي تذكرني فيها كل اثنين", "دوامي", recent)
        assertEquals("reminder", result.getString("classification"))
        assertTrue(result.getString("reply").contains("18:30"))
        assertTrue(result.getString("reply").contains("الاثنين"))
    }

    @Test
    fun cleansNaturalSearchQuery() {
        val result = LocalAssistantFallback.analyze("وين حطيت جواز السفر؟", "أوراقي")
        assertEquals("search", result.getString("classification"))
        val action = result.getJSONArray("actions").getJSONObject(0)
        assertEquals("search", action.getString("type"))
        assertEquals("جواز السفر", action.getJSONObject("args").getString("query"))
    }

    @Test
    fun understandsRenameAndMoveCommands() {
        val rename = LocalAssistantFallback.analyze("غير اسم المساحة الى أوراق رضوان", "ملاحظات")
        assertEquals("rename_space", rename.getJSONArray("actions").getJSONObject(0).getString("type"))
        val move = LocalAssistantFallback.analyze("انقل هاد الى أوراقي", "ملاحظات")
        assertEquals("move_last_item", move.getJSONArray("actions").getJSONObject(0).getString("type"))
    }
}
