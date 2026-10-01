package app.masahati.mobile.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanGuardPolicyTest {
    @Test fun alteredOfficialReferenceNumberRejectsProcessing() {
        val result=ScanGuardPolicy.evaluate(listOf("Betriebsnummer 73071446" to .98),listOf("Betriebsnummer 73071448" to .96),emptySet(),emptySet())
        assertFalse(result.accepted);assertEquals(1,result.numericChanges)
    }
    @Test fun lossOfOnlyOneBarcodeRejectsProcessing() {
        val result=ScanGuardPolicy.evaluate(emptyList(),emptyList(),setOf("a","b"),setOf("b"))
        assertFalse(result.accepted);assertEquals(setOf("a"),result.lostBarcodes)
    }
    @Test fun unreliableOcrIsNotTreatedAsGroundTruth() {
        assertTrue(ScanGuardPolicy.evaluate(listOf("73071446" to .55),listOf("73071448" to .60),emptySet(),emptySet()).accepted)
    }
    @Test fun whitespaceAndCaseDoNotInventWarnings() {
        assertTrue(ScanGuardPolicy.evaluate(listOf("Betriebsnummer: 73071446" to .99),listOf("BETRIEBSNUMMER 73071446" to .98),setOf("qr"),setOf("qr")).accepted)
    }
    @Test fun ctcKeepsRepeatedDigitsSeparatedByBlankAndUnicodeTokens() {
        val vocab=listOf("","8","ب"," ");val chosen=listOf(1,1,0,1,0,2,2,0,3)
        val probs=FloatArray(chosen.size*vocab.size) { .01f }
        chosen.forEachIndexed { i,value -> probs[i*vocab.size+value]=.97f }
        val result=ScanCtc.decode(probs,chosen.size,vocab.size,vocab)
        assertEquals("88ب",result.first);assertTrue(result.second>.96)
    }
}
