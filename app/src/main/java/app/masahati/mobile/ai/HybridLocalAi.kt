package app.masahati.mobile.ai

import android.content.Context
import org.json.JSONObject

class HybridLocalAi(context: Context) {
    private val local: LocalAiEngine = LiteRtLmLocalEngine(context)

    fun isLocalReady(): Boolean = local.isReady()

    fun generate(request: MasahatiAiRequest): JSONObject? =
        if (local.isReady()) local.generate(request) else null

    fun close() = local.close()
}
