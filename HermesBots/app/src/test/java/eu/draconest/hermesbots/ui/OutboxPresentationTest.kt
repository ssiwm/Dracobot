package eu.draconest.hermesbots.ui

import eu.draconest.hermesbots.data.QueuedPrompt
import eu.draconest.hermesbots.data.QueuedPromptDeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxPresentationTest {
    @Test
    fun pendingEntryIsVisibleButCannotBeResentAsNew() {
        val presentation = outboxActionAvailability(
            QueuedPrompt(
                id = "pending",
                storedSessionId = "stored-a",
                text = "first",
                profileName = "architect",
                deliveryState = QueuedPromptDeliveryState.Pending
            )
        )

        assertEquals("Oczekuje na dostarczenie", presentation.stateLabel)
        assertTrue(presentation.canOpenConversation)
        assertFalse(presentation.canResendAsNew)
    }

    @Test
    fun heldEntryCanBeResentOnlyAsAnExplicitNewMessage() {
        val rejected = outboxActionAvailability(
            QueuedPrompt(
                id = "rejected",
                storedSessionId = "stored-a",
                text = "first",
                profileName = "architect",
                deliveryState = QueuedPromptDeliveryState.Rejected,
                deliveryDetail = "Gateway refused this prompt"
            )
        )
        val indeterminate = outboxActionAvailability(
            QueuedPrompt(
                id = "indeterminate",
                storedSessionId = "stored-a",
                text = "first",
                profileName = "architect",
                deliveryState = QueuedPromptDeliveryState.Indeterminate
            )
        )

        assertEquals("Odrzucono przez gateway", rejected.stateLabel)
        assertEquals("Gateway refused this prompt", rejected.deliveryDetail)
        assertTrue(rejected.canResendAsNew)
        assertEquals("Niepewny rezultat — sprawdź historię", indeterminate.stateLabel)
        assertTrue(indeterminate.canResendAsNew)
    }

    @Test
    fun legacyEntryWithoutProfileCannotOpenAnUnrelatedConversation() {
        val presentation = outboxActionAvailability(
            QueuedPrompt(
                id = "legacy",
                storedSessionId = "stored-a",
                text = "first",
                deliveryState = QueuedPromptDeliveryState.Rejected
            )
        )

        assertFalse(presentation.canOpenConversation)
        assertFalse(presentation.canResendAsNew)
    }
}
