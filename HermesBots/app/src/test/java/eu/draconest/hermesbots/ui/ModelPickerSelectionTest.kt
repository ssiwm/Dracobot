package eu.draconest.hermesbots.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerSelectionTest {
    @Test
    fun sameModelIdFromAnotherProviderIsNotMarkedActive() {
        assertFalse(
            isCurrentModelSelection(
                provider = "openrouter",
                model = "openai/gpt-5.6-luna",
                currentProvider = "nous",
                currentModel = "openai/gpt-5.6-luna"
            )
        )
    }

    @Test
    fun exactProviderAndNestedModelIdIsMarkedActive() {
        assertTrue(
            isCurrentModelSelection(
                provider = "nous",
                model = "openai/gpt-5.6-luna",
                currentProvider = "nous",
                currentModel = "openai/gpt-5.6-luna"
            )
        )
    }

    @Test
    fun providerAliasMatchesCanonicalSessionProvider() {
        assertTrue(
            isCurrentModelSelection(
                provider = "my-gateway",
                providerAliases = listOf("custom:my-gateway"),
                model = "openai/gpt-5.6-luna",
                currentProvider = "custom:my-gateway",
                currentModel = "openai/gpt-5.6-luna"
            )
        )
    }
}
