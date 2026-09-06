package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlphaImporterSafetyTest {
    @Test
    fun acceptsOnlyKnownBackupPaths() {
        assertEquals("data/masahati.json", AlphaImporter.sanitizeEntry("data/masahati.json"))
        assertEquals("files/12-contract.pdf", AlphaImporter.sanitizeEntry("files/12-contract.pdf"))
        assertEquals("README.txt", AlphaImporter.sanitizeEntry("README.txt"))
    }

    @Test
    fun rejectsTraversalAndUnknownPaths() {
        assertNull(AlphaImporter.sanitizeEntry("../evil.txt"))
        assertNull(AlphaImporter.sanitizeEntry("files/../../evil.txt"))
        assertNull(AlphaImporter.sanitizeEntry("random/payload.bin"))
        assertNull(AlphaImporter.sanitizeEntry(".."))
    }
}
