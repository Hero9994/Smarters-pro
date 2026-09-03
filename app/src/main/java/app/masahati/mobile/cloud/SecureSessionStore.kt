package app.masahati.mobile.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val userId: String,
    val email: String?
)

class SecureSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(session: AuthSession) {
        val payload = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("expires_at", session.expiresAtEpochSeconds)
            .put("user_id", session.userId)
            .put("email", session.email)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(payload)
        prefs.edit {
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(KEY_PAYLOAD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
    }

    fun load(): AuthSession? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val encrypted = prefs.getString(KEY_PAYLOAD, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            val json = JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
            AuthSession(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresAtEpochSeconds = json.getLong("expires_at"),
                userId = json.getString("user_id"),
                email = json.optString("email").takeIf { it.isNotBlank() && it != "null" }
            )
        }.getOrElse {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit {
            remove(KEY_IV)
            remove(KEY_PAYLOAD)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "masahati_secure_session"
        private const val KEY_ALIAS = "masahati.cloud.session.aes.v1"
        private const val KEY_IV = "session_iv"
        private const val KEY_PAYLOAD = "session_payload"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
