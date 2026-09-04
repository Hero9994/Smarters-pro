package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSearchTest {
    @Test fun normalizesArabicVariantsAndDiacritics() {
        assertEquals("ايجار البيت", SmartSearch.normalize("إيجارُ الـبيت"))
        assertEquals("الاوراق", SmartSearch.normalize("الأوراق"))
    }

    @Test fun filenameAndTagsRankAboveLooseOcrMatch() {
        val filenameHit = SmartSearch.score("عقد الإيجار", "عقد الإيجار 2026.pdf", "مستند، عقد", "document", null, null, null)
        val ocrOnly = SmartSearch.score("عقد الإيجار", "Scan-001.pdf", null, "document", null, null, "هذه نسخة من عقد الايجار الخاص بالشقة")
        assertTrue(filenameHit > ocrOnly)
    }

    @Test fun toleratesOneCharacterOcrTypo() {
        assertTrue(SmartSearch.score("rechnung", null, null, null, null, null, "Rechnunq vom 04.09.2026") > 0)
    }

    @Test fun findsGermanWordInsideCompound() {
        assertTrue(SmartSearch.score("vertrag", null, null, null, null, null, "Mietvertragsnummer 91827") > 0)
    }
    @Test fun findsMedicalTransportAcrossArabicAndGerman() {
        assertTrue(
            SmartSearch.score(
                "نقل من المنزل إلى الطبيب",
                "Scan.pdf",
                null,
                "document",
                null,
                null,
                "Verordnung einer Krankenbeförderung zum Arzt"
            ) > 0
        )
    }

    @Test fun findsPassportAcrossLanguages() {
        assertTrue(
            SmartSearch.score(
                "جواز السفر",
                "Reisepass.pdf",
                null,
                "document",
                null,
                null,
                null
            ) > 0
        )
    }

}
