package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class FoundationConfigTest {

    @Test
    fun applicationId_contract_is_stable() {
        assertEquals("app.masahati.mobile", FoundationConfig.EXPECTED_APPLICATION_ID)
    }

    @Test
    fun version_label_matches_foundation_release() {
        assertEquals("v0.2 Android Foundation", FoundationConfig.VERSION_LABEL)
    }
}
