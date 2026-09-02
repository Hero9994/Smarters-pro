package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

class MasahatiFormatTest {
    @Test
    fun safeFileName_removesUnsafeCharacters() {
        val result=MasahatiFormat.safeFileName("my:bad/file?.pdf")
        assertFalse(result.contains(":"))
        assertFalse(result.contains("/"))
        assertFalse(result.contains("?"))
        assertEquals("my_bad_file_.pdf",result)
    }

    @Test
    fun shortTime_hasExpectedShape() {
        val result=MasahatiFormat.shortTime(0L,Locale.GERMANY)
        assertEquals(5,result.length)
        assertEquals(':',result[2])
    }
}
