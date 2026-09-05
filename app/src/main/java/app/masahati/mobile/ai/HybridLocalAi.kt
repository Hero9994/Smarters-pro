package app.masahati.mobile.ai

import android.content.Context
import org.json.JSONObject

class HybridLocalAi(
    context: Context,
    private val spec: LocalModelSpec = LocalModelCatalog.default
) {
    private val local: LocalAiEngine = LiteRtLmLocalEngine(context, spec)

    fun isLocalReady(): Boolean = local.isReady()

    fun modelId(): String = spec.id

    fun generate(request: MasahatiAiRequest): JSONObject? =
        if (local.isReady()) local.generate(request) else null

    fun close() = local.close()
}
