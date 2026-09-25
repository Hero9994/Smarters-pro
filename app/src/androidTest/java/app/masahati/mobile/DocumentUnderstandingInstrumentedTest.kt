package app.masahati.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentUnderstandingInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MasahatiDatabase
    @Before fun setUp() { context.deleteDatabase("masahati_v05.db"); db = MasahatiDatabase(context) }
    @After fun tearDown() { db.close(); context.deleteDatabase("masahati_v05.db") }

    private val instruction = "Bitte Unterlagen einreichen."
    private fun analysis(excerpt: String = instruction): JSONObject = JSONObject().put("ok", true).put("classification", "document")
        .put("summary", "أرسل الأوراق المطلوبة")
        .put("document", JSONObject().put("schema_version", 3).put("analysis_status", "analyzed")
            .put("smart_title", "طلب مستندات").put("doc_type", "official_notice").put("doc_type_label", "قرار رسمي")
            .put("topic_label", "المعاملات الرسمية").put("action_status", "required").put("action_required", true)
            .put("action_text", instruction).put("due_date", "2026-10-15").put("issue_codes", JSONArray())
            .put("evidence", JSONArray().put(JSONObject().put("field", "action_text").put("excerpt", excerpt))))

    @Test fun rereadingRetainsTaskIdentityAndDoesNotReopenCompletedTask() {
        val space = db.createSpace("أوراقي")
        val file = db.insertFile(space, "user", "scan-01.pdf", "/fixture", "application/pdf", instruction)
        AlphaDocumentProcessor.applyAgentResult(db, file, analysis())
        val task = db.listOpenActionItems().single()
        AlphaDocumentProcessor.applyAgentResult(db, file, analysis())
        assertEquals(task.id, db.listOpenActionItems().single().id)
        db.completeActionItem(task.id)
        AlphaDocumentProcessor.applyAgentResult(db, file, analysis())
        assertTrue(db.listOpenActionItems().isEmpty())
        assertEquals("done", db.getActionItem(task.id)!!.status)
        assertEquals(1, db.listActionItemsForMessage(file).size)
    }

    @Test fun genericReplyDoesNotEraseStructuredDocumentOrTasks() {
        val space = db.createSpace("أوراقي")
        val file = db.insertFile(space, "user", "personal-name.pdf", "/fixture", "application/pdf", instruction)
        AlphaDocumentProcessor.applyAgentResult(db, file, analysis())
        val before = db.getDocumentMeta(file)!!.extractedJson
        AlphaDocumentProcessor.applyAgentResult(db, file, JSONObject().put("classification", "document").put("reply", "قرأت الورقة"))
        assertEquals(before, db.getDocumentMeta(file)!!.extractedJson)
        assertEquals(1, db.listOpenActionItems().size)
        assertEquals("personal-name.pdf", db.getMessage(file)!!.displayName)
    }

    @Test fun fabricatedEvidenceAndPartialOcrCannotCreateTasks() {
        val space = db.createSpace("أوراقي")
        val file = db.insertFile(space, "user", "scan-01.pdf", "/fixture", "application/pdf", instruction)
        AlphaDocumentProcessor.applyAgentResult(db, file, analysis("invented evidence"))
        assertTrue(db.listOpenActionItems().isEmpty())
        db.updateExtractionNote(file, "الصفحة الثانية غير مقروءة")
        AlphaDocumentProcessor.applyAgentResult(db, file, analysis())
        assertTrue(db.listOpenActionItems().isEmpty())
    }

    @Test fun detailsKeepAmountRolesAndReviewWarningInsteadOfAccuracyPercentage() {
        val doc = analysis().getJSONObject("document")
            .put("analysis_status", "limited").put("confidence", 0.5)
            .put("amounts", JSONArray()
                .put(JSONObject().put("role", "total").put("label", "الإجمالي").put("value", "119,00 EUR"))
                .put(JSONObject().put("role", "due").put("label", "المتبقي للدفع").put("value", "0,00 EUR")))
            .put("review_reasons", JSONArray().put("راجع التاريخ في الأصل"))
        val text = DocumentUnderstanding.details(doc).joinToString("\n")
        assertTrue(text.contains("الإجمالي: 119,00 EUR"))
        assertTrue(text.contains("المتبقي للدفع: 0,00 EUR"))
        assertTrue(text.contains("قراءة أولية"))
        assertTrue(text.contains("راجع التاريخ في الأصل"))
        assertFalse(text.contains("50%"))
    }
}
