package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
