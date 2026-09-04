package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelMetadataTest {
    @Test
    fun pinnedLocalModelMetadataIsStable() {
        assertEquals(
            "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            AiModelManager.MODEL_FILE_NAME
        )
        assertEquals(977_184_032L, AiModelManager.MODEL_SIZE_BYTES)
        assertEquals(64, AiModelManager.MODEL_SHA256.length)
        assertTrue(AiModelManager.MODEL_SHA256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(AiModelManager.MODEL_URL.startsWith("https://huggingface.co/"))
    }
}
