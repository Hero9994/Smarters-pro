package app.masahati.mobile

import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaAnswerGroundingTest {
    @Test
    fun expiryQuestionPrefersExpiryEvidence() {
        assertTrue("expiry_date" in AlphaAnswerGrounding.preferredFields("متى بينتهي هاد العقد؟"))
    }

    @Test
    fun amountQuestionPrefersAmountEvidence() {
        assertTrue("amount_text" in AlphaAnswerGrounding.preferredFields("كم المبلغ المطلوب؟"))
    }

    @Test
    fun actionQuestionPrefersActionEvidence() {
        assertTrue("action_text" in AlphaAnswerGrounding.preferredFields("شو لازم أعمل بهالورقة؟"))
    }
}
