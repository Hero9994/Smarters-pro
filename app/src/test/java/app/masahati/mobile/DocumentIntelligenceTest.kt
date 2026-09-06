package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIntelligenceTest {
    @Test
    fun recognizesCompoundQuestionButDoesNotTreatItAsNameOnly() {
        val question = "شو محتوى الورقة وشو سميتها؟"
        assertTrue(DocumentIntelligence.isDocumentQuestion(question))
        assertFalse(DocumentIntelligence.asksOnlyForName(question))
    }

    @Test
    fun recognizesNameOnlyQuestion() {
        assertTrue(DocumentIntelligence.asksOnlyForName("شو سميتها؟"))
    }

    @Test
    fun normalNoteIsNotDocumentQuestion() {
        assertFalse(DocumentIntelligence.isDocumentQuestion("بدي اشتري خبز وبنزين"))
    }

    @Test
    fun groundsContractEndDateNearExplicitLabel() {
        val ocr = "Mietvertrag. Vertragsbeginn 01.10.2026. Vertragsende 30.09.2027."
        assertEquals(
            "end" to "30.09.2027",
            DocumentIntelligence.resolveGroundedDate("متى بينتهي العقد؟", ocr)
        )
    }

    @Test
    fun groundsContractStartDateNearExplicitLabel() {
        val ocr = "Mietvertrag. Vertragsbeginn 01.10.2026. Vertragsende 30.09.2027."
        assertEquals(
            "start" to "01.10.2026",
            DocumentIntelligence.resolveGroundedDate("متى بيبدأ العقد؟", ocr)
        )
    }

    @Test
    fun multipleUnlabelledDatesAreNotGuessed() {
        val ocr = "Termine: 01.10.2026 und 30.09.2027"
        assertNull(DocumentIntelligence.resolveGroundedDate("متى بينتهي؟", ocr))
    }

    @Test
    fun medicalTransportScanIsNotMisclassifiedAsWorkScheduleOffline() {
        val doc = MessageRow(
            id = 20,
            spaceId = 3,
            role = "user",
            kind = "file",
            text = "",
            filePath = "/tmp/transport.pdf",
            mimeType = "application/pdf",
            displayName = "Scan.pdf",
            ocrText = "Genehmigung zur Krankenbeförderung vom Wohnort zum Arzt. Patient.",
            classification = null,
            tags = null,
            summary = null,
            starred = false,
            createdAt = 1
        )
        val result = DocumentIntelligence.knownDocumentResult(doc)
        assertNotNull(result)
        assertEquals("document", result!!.getString("classification"))
        assertTrue(result.getString("reply").contains("نقل"))
        assertFalse(result.getString("reply").contains("دوام"))
        assertTrue(result.getJSONArray("labels").toString().contains("نقل مرضى"))
    }

    @Test
    fun rentalContractScanExtractsGroundedStartAndEndDatesOffline() {
        val doc = MessageRow(
            id = 21,
            spaceId = 3,
            role = "user",
            kind = "file",
            text = "",
            filePath = "/tmp/vertrag.pdf",
            mimeType = "application/pdf",
            displayName = "Mietvertrag.pdf",
            ocrText = "Mietvertrag. Vertragsbeginn 01.10.2026. Vertragsende 30.09.2027.",
            classification = null,
            tags = null,
            summary = null,
            starred = false,
            createdAt = 1
        )
        val result = DocumentIntelligence.knownDocumentResult(doc)
        assertNotNull(result)
        assertTrue(result!!.getString("summary").contains("01.10.2026"))
        assertTrue(result.getString("summary").contains("30.09.2027"))
        assertTrue(result.getJSONArray("labels").toString().contains("عقد إيجار"))
    }

    @Test
    fun unknownDocumentDoesNotInventOfflineMeaning() {
        val doc = MessageRow(
            id = 22,
            spaceId = 3,
            role = "user",
            kind = "file",
            text = "",
            filePath = "/tmp/unknown.pdf",
            mimeType = "application/pdf",
            displayName = "unknown.pdf",
            ocrText = "ABC 123 XYZ",
            classification = null,
            tags = null,
            summary = null,
            starred = false,
            createdAt = 1
        )
        assertNull(DocumentIntelligence.knownDocumentResult(doc))
    }

}
