package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAssistantFallbackTest {
    @Test
    fun mainzDoesNotBecomeSearch() {
        val result = LocalAssistantFallback.analyze(
            "رضوان عنده مباراة الأحد الساعة 11 ضد ماينز",
            "مباريات رضوان"
        )
        assertEquals("note", result.getString("classification"))
        assertTrue(result.getString("reply").contains("11:00"))
        assertFalse(result.getString("reply").contains("سأبحث"))
    }

    @Test
    fun ideaMentioningReminderAndContractStaysIdea() {
        val result = LocalAssistantFallback.analyze(
            "فكرة للتطبيق: لما أصور عقد يطلعلي تاريخ الانتهاء ويقترح تذكير قبل شهر",
            "مشروعي"
        )
        assertEquals("idea", result.getString("classification"))
        assertTrue(result.getString("reply").contains("فهمت الفكرة"))
    }

    @Test
    fun taskIsSpecificNotGeneric() {
        val result = LocalAssistantFallback.analyze(
            "لازم اتصل بالتأمين بكرا وأسألهم عن الموافقة",
            "شغلاتي"
        )
        assertEquals("task", result.getString("classification"))
        assertTrue(result.getString("reply").contains("اتصل بالتأمين"))
        assertFalse(result.getString("reply").contains("فهمت المحتوى"))
    }

    @Test
    fun correctionOverridesWrongWorkClassification() {
        val doc = MessageRow(
            id = 1,
            spaceId = 9,
            role = "user",
            kind = "file",
            text = "",
            filePath = "/tmp/Scan.pdf",
            mimeType = "application/pdf",
            displayName = "Scan.pdf",
            ocrText = "Krankenbeförderung Arzt Wohnung",
            classification = "work_schedule",
            tags = "دوام",
            summary = "جدول دوام",
            starred = false,
            createdAt = 1
        )
        val result = LocalAssistantFallback.analyze(
            "لا مو جدول دوام، هاي موافقة نقل للمريض عند الطبيب",
            "مباريات رضوان",
            listOf(doc)
        )
        assertEquals("document", result.getString("classification"))
        assertTrue(result.getString("reply").contains("ليس جدول دوام"))
        assertTrue(result.getString("summary").contains("موافقة"))
        assertEquals("enrich_previous_document", result.getJSONArray("actions").getJSONObject(0).getString("type"))
    }

    @Test
    fun createSpaceProducesExecutableAction() {
        val result = LocalAssistantFallback.analyze(
            "اعمللي مساحة اسمها أوراق رضوان",
            "عام"
        )
        assertEquals("command", result.getString("classification"))
        val action = result.getJSONArray("actions").getJSONObject(0)
        assertEquals("create_space", action.getString("type"))
        assertEquals("أوراق رضوان", action.getJSONObject("args").getString("name"))
    }

    @Test
    fun dentistAppointmentExtractsDayAndTime() {
        val result = LocalAssistantFallback.analyze(
            "عندي موعد عند طبيب الأسنان الثلاثاء الساعة 16:20",
            "مواعيد"
        )
        assertEquals("note", result.getString("classification"))
        assertTrue(result.getString("reply").contains("الثلاثاء"))
        assertTrue(result.getString("reply").contains("16:20"))
    }

    @Test
    fun opponentFollowUpUsesRecentMatchContext() {
        val prior = MessageRow(
            id = 2,
            spaceId = 4,
            role = "user",
            kind = "text",
            text = "رضوان عنده مباراة الأحد الساعة 11 ضد ماينز",
            filePath = null,
            mimeType = null,
            displayName = null,
            ocrText = null,
            classification = "note",
            tags = "مباراة، الأحد",
            summary = "مباراة رضوان ضد ماينز الأحد الساعة 11",
            starred = false,
            createdAt = 1
        )
        val result = LocalAssistantFallback.analyze("ضد مين؟", "مباريات رضوان", listOf(prior))
        assertEquals("note", result.getString("classification"))
        assertTrue(result.getString("reply").contains("ماينز"))
        assertFalse(result.getString("reply").contains("سأبحث"))
    }

    @Test
    fun archiveSpaceProducesExecutableAction() {
        val result = LocalAssistantFallback.analyze("أرشف هالمساحة", "قديم")
        assertEquals("command", result.getString("classification"))
        assertEquals(
            "archive_space",
            result.getJSONArray("actions").getJSONObject(0).getString("type")
        )
    }


    @Test
    fun renameLastDocumentProducesExecutableAction() {
        val result = LocalAssistantFallback.analyze(
            "غير اسم آخر مستند إلى موافقة نقل طبي.pdf",
            "أوراقي"
        )
        assertEquals("command", result.getString("classification"))
        val action = result.getJSONArray("actions").getJSONObject(0)
        assertEquals("rename_last_document", action.getString("type"))
        assertEquals("موافقة نقل طبي.pdf", action.getJSONObject("args").getString("new_name"))
    }

    @Test
    fun moveLastDocumentProducesExecutableAction() {
        val result = LocalAssistantFallback.analyze(
            "انقل آخر مستند إلى مساحة أوراق رضوان",
            "عام"
        )
        val action = result.getJSONArray("actions").getJSONObject(0)
        assertEquals("move_last_document", action.getString("type"))
        assertEquals("أوراق رضوان", action.getJSONObject("args").getString("target_space"))
    }

    @Test
    fun renameSpaceProducesExecutableAction() {
        val result = LocalAssistantFallback.analyze(
            "غير اسم هالمساحة إلى شغل مهم",
            "قديم"
        )
        val action = result.getJSONArray("actions").getJSONObject(0)
        assertEquals("rename_space", action.getString("type"))
        assertEquals("شغل مهم", action.getJSONObject("args").getString("new_name"))
    }

    @Test
    fun moveLastItemProducesExecutableAction() {
        val result = LocalAssistantFallback.analyze(
            "انقل آخر شي إلى مساحة يومي",
            "عام"
        )
        val action = result.getJSONArray("actions").getJSONObject(0)
        assertEquals("move_last_item", action.getString("type"))
        assertEquals("يومي", action.getJSONObject("args").getString("target_space"))
    }

}
