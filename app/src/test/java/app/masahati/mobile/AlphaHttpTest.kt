package app.masahati.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaHttpTest {
    @Test
    fun onlyHttpAndHttpsAreAcceptedForWebClipper() {
        assertTrue(AlphaHttp.isSafeWebUrl("https://example.com/a"))
        assertTrue(AlphaHttp.isSafeWebUrl("http://example.com"))
        assertFalse(AlphaHttp.isSafeWebUrl("file:///etc/passwd"))
        assertFalse(AlphaHttp.isSafeWebUrl("javascript:alert(1)"))
    }
}
