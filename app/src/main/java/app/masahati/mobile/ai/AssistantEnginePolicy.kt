package app.masahati.mobile.ai

/** A successful HTTP response may still contain only the server's emergency rules. */
object AssistantEnginePolicy {
    fun preferRemote(ok: Boolean, engine: String, model: String): Boolean {
        if (!ok) return false
        return !engine.contains("fallback", ignoreCase = true) &&
            !model.startsWith("rules", ignoreCase = true)
    }
}
