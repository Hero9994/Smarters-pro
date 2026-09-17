package app.masahati.mobile.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantEnginePolicyTest {
    @Test fun successfulRuleFallbackDoesNotSuppressInstalledLocalModel() {
        assertFalse(AssistantEnginePolicy.preferRemote(true, "edge-fallback-v7", "rules-v7"))
    }
    @Test fun usefulDeterministicSearchStillTakesPriority() {
        assertTrue(AssistantEnginePolicy.preferRemote(true, "hybrid-router-v2", "deterministic"))
    }
    @Test fun realModelResponseTakesPriorityButFailedResponseDoesNot() {
        assertTrue(AssistantEnginePolicy.preferRemote(true, "cloud", "model"))
        assertFalse(AssistantEnginePolicy.preferRemote(false, "cloud", "model"))
    }
}
