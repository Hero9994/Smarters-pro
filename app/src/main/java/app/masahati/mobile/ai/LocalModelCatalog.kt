package app.masahati.mobile.ai

data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val sha256: String,
    val maxTokens: Int,
    val supportsVision: Boolean
)

object LocalModelCatalog {
    val QWEN3_1_7B_DYNAMIC = LocalModelSpec(
        id = "qwen3-1.7b-dynamic-int4",
        displayName = "Qwen3 1.7B LiteRT-LM",
        fileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm?download=true",
        expectedBytes = 977_000_000L,
        sha256 = "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b",
        maxTokens = 4096,
        supportsVision = false
    )

    val GEMMA4_E2B = LocalModelSpec(
        id = "gemma4-e2b",
        displayName = "Gemma 4 E2B LiteRT-LM",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
        expectedBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        maxTokens = 8192,
        supportsVision = true
    )

    val benchmarkCandidates = listOf(QWEN3_1_7B_DYNAMIC, GEMMA4_E2B)
    val default = QWEN3_1_7B_DYNAMIC
}
