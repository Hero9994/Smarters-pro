package app.masahati.mobile

import java.io.InputStream
import java.io.FilterInputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object AlphaBackupCrypto {
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val ITERATIONS = 180_000
    private const val KEY_BITS = 256

    fun encrypt(password: CharArray, output: OutputStream, writePlainZip: (OutputStream) -> Unit) {
        require(password.size >= 6) { "كلمة المرور يجب أن تكون 6 أحرف على الأقل" }
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val key = derive(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }

        output.write(MAGIC)
        output.write(salt)
        output.write(iv)
        CipherOutputStream(output, cipher).use { encrypted ->
            writePlainZip(encrypted)
        }
    }

    fun decrypt(password: CharArray, input: InputStream, readPlainZip: (InputStream) -> Unit) {
        val magic = input.readExact(MAGIC.size)
        require(magic.contentEquals(MAGIC)) { "هذا الملف ليس نسخة مساحاتي مشفرة" }
        val salt = input.readExact(SALT_SIZE)
        val iv = input.readExact(IV_SIZE)
        val key = derive(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        CipherInputStream(input, cipher).use { decrypted ->
            val nonClosing = object : FilterInputStream(decrypted) {
                override fun close() = Unit
            }
            readPlainZip(nonClosing)
            while (decrypted.read() != -1) {
                // Drain to force GCM tag verification before success is reported.
            }
        }
    }

    fun hasEncryptedHeader(prefix: ByteArray): Boolean =
        prefix.size >= MAGIC.size && prefix.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun InputStream.readExact(size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(out, offset, size - offset)
            if (read < 0) error("ملف النسخة المشفرة غير مكتمل")
            offset += read
        }
        return out
    }
}
