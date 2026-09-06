package app.masahati.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration

object AlphaHttp {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(8))
            .readTimeout(Duration.ofSeconds(32))
            .writeTimeout(Duration.ofSeconds(15))
            .callTimeout(Duration.ofSeconds(40))
            .retryOnConnectionFailure(true)
            .build()
    }

    fun postJson(
        url: String,
        json: String,
        apiKey: String? = null,
        readTimeoutSeconds: Long = 32
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                if (!apiKey.isNullOrBlank()) header("apikey", apiKey)
            }
            .post(json.toRequestBody(jsonType))
            .build()

        val scoped = client.newBuilder()
            .readTimeout(Duration.ofSeconds(readTimeoutSeconds.coerceIn(1, 90)))
            .callTimeout(Duration.ofSeconds((readTimeoutSeconds + 8).coerceIn(10, 100)))
            .build()

        scoped.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${body.take(300)}")
            }
            return body
        }
    }

    fun getText(
        url: String,
        maxBytes: Int = 1_500_000,
        userAgent: String = "Masahati-Alpha/0.1"
    ): String {
        require(isSafeWebUrl(url)) { "الرابط غير مدعوم" }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,text/plain,application/xhtml+xml;q=0.9,*/*;q=0.3")
            .header("User-Agent", userAgent)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val stream = response.body?.byteStream() ?: return ""
            val bytes = stream.readAtMost(maxBytes.coerceIn(1024, 4_000_000))
            return bytes.toString(Charsets.UTF_8)
        }
    }

    internal fun isSafeWebUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://")
    }

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(32 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }
}
