package app.masahati.mobile.ai

import app.masahati.mobile.MessageRow
import org.json.JSONObject

data class MasahatiAiRequest(
    val userText: String,
    val spaceTitle: String,
    val recent: List<MessageRow>,
    val focusedDocument: MessageRow?,
    val nowIso: String,
    val timezone: String,
    val availableSpaces: List<String> = emptyList()
)

interface LocalAiEngine {
    fun isReady(): Boolean
    fun generate(request: MasahatiAiRequest): JSONObject?
    fun close()
}
