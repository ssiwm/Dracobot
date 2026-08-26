package eu.draconest.hermesbots.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kontrakt gatewaya: config.set nie przyjmuje "provider/model" jako modelu.
 * Id modelu i provider muszą pozostać rozdzielone, identycznie jak w Desktop.
 */
class ModelSwitchProtocolTest {
    @Test
    fun buildsSessionScopedCommandWithExplicitProvider() {
        assertEquals(
            "gpt-5.6-luna --provider openai-codex --session",
            buildSessionModelSwitchCommand("openai-codex", "gpt-5.6-luna")
        )
    }

    @Test
    fun preservesNestedModelIdForAggregatorProvider() {
        assertEquals(
            "openai/gpt-5.6-luna --provider nous --session",
            buildSessionModelSwitchCommand("nous", "openai/gpt-5.6-luna")
        )
    }

    @Test
    fun turnsRpcFailureIntoReadableUiMessage() {
        assertEquals(
            "Model niedostępny (5001)",
            modelSwitchErrorForUi(
                IllegalStateException("RPC config.set: Model niedostępny (5001)")
            )
        )
    }
}
