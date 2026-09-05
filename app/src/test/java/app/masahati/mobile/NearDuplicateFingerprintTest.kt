package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearDuplicateFingerprintTest {
    private val base = """
        Mietvertrag zwischen Beispiel GmbH und Max Mustermann.
        Vertragsnummer MV-4488. Vertragsbeginn 01.10.2026.
        Monatliche Miete 900 EUR. Die Miete ist jeweils zum dritten Werktag fällig.
        Vertragsende 30.09.2027. Anschrift Musterstraße 12, 66892 Beispielort.
    """.trimIndent()

    @Test
    fun identicalTextHasZeroDistance() {
        val fp = NearDuplicateFingerprint.fingerprint(base)
        assertNotNull(fp)
        assertEquals(0, NearDuplicateFingerprint.distance(fp!!, fp))
    }

    @Test
    fun smallOcrVariationStaysNear() {
        val a = NearDuplicateFingerprint.fingerprint(base)!!
        val b = NearDuplicateFingerprint.fingerprint(
            base.replace("Mustermann", "Musterman")
                .replace("Musterstraße", "Musterstrasse")
                .replace("900 EUR", "900,00 EUR")
        )!!
        assertTrue((NearDuplicateFingerprint.distance(a, b) ?: 64) <= 7)
    }

    @Test
    fun shortTextIsNotFingerprintable() {
        assertEquals(null, NearDuplicateFingerprint.fingerprint("kurze Notiz"))
    }
}
