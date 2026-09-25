package app.masahati.mobile.ai

/** A successful HTTP response may still contain only the server's emergency rules. */
object AssistantEnginePolicy {
    fun preferRemote(ok: Boolean, engine: String, model: String, documentSchema: Int = 0): Boolean {
        if (!ok) return false
        // A grounded document record must not be replaced by a generic chat reply.
        // Its own analysis_status/reply disclose limited service availability.
        if (documentSchema >= 3) return true
        return !engine.contains("fallback", ignoreCase = true) &&
            !model.startsWith("rules", ignoreCase = true)
    }
}
