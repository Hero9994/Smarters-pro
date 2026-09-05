package app.masahati.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AlphaBackupCryptoTest {
    @Test
    fun encryptedBackupRoundTrips() {
        val plain = "PK\u0003\u0004 fake zip payload".toByteArray()
        val encrypted = ByteArrayOutputStream()
        AlphaBackupCrypto.encrypt("secret123".toCharArray(), encrypted) { out -> out.write(plain) }

        val bytes = encrypted.toByteArray()
        assertTrue(AlphaBackupCrypto.hasEncryptedHeader(bytes.copyOfRange(0, 4)))

        val recovered = ByteArrayOutputStream()
        AlphaBackupCrypto.decrypt("secret123".toCharArray(), ByteArrayInputStream(bytes)) { input ->
            input.copyTo(recovered)
        }
        assertArrayEquals(plain, recovered.toByteArray())
    }

    @Test
    fun wrongPasswordFailsAuthentication() {
        val plain = ByteArray(4096) { (it % 251).toByte() }
        val encrypted = ByteArrayOutputStream()
        AlphaBackupCrypto.encrypt("correct123".toCharArray(), encrypted) { out -> out.write(plain) }

        var failed = false
        try {
            AlphaBackupCrypto.decrypt(
                "wrong123".toCharArray(),
                ByteArrayInputStream(encrypted.toByteArray())
            ) { input -> input.copyTo(ByteArrayOutputStream()) }
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

}
