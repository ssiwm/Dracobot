package eu.draconest.hermesbots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModelSyncTest {
    @Test
    fun supersededSessionResponseIsNotApplied() {
        assertFalse(shouldApplySessionUpdate(expectedGeneration = 4, currentGeneration = 5))
    }

    @Test
    fun activeSessionResponseIsApplied() {
        assertTrue(shouldApplySessionUpdate(expectedGeneration = 5, currentGeneration = 5))
    }

    @Test
    fun reconnectResultRequiresCurrentSessionGenerationAndRevision() {
        assertTrue(
            shouldApplyReconnectResult(
                expectedRuntimeSessionId = "session-a",
                activeRuntimeSessionId = "session-a",
                expectedStoredSessionId = "stored-a",
                activeStoredSessionId = "stored-a",
                expectedGeneration = 9,
                currentGeneration = 9,
                expectedRevision = 17,
                currentRevision = 17
            )
        )
        assertFalse(
            shouldApplyReconnectResult(
                expectedRuntimeSessionId = "session-a",
                activeRuntimeSessionId = "session-b",
                expectedStoredSessionId = "stored-a",
                activeStoredSessionId = "stored-a",
                expectedGeneration = 9,
                currentGeneration = 9,
                expectedRevision = 17,
                currentRevision = 17
            )
        )
        assertFalse(
            shouldApplyReconnectResult(
                expectedRuntimeSessionId = "session-a",
                activeRuntimeSessionId = "session-a",
                expectedStoredSessionId = "stored-a",
                activeStoredSessionId = "stored-a",
                expectedGeneration = 9,
                currentGeneration = 10,
                expectedRevision = 17,
                currentRevision = 17
            )
        )
    }

    @Test
    fun newTurnForSameSessionInvalidatesInFlightReconnect() {
        assertFalse(
            shouldApplyReconnectResult(
                expectedRuntimeSessionId = "session-a",
                activeRuntimeSessionId = "session-a",
                expectedStoredSessionId = "stored-a",
                activeStoredSessionId = "stored-a",
                expectedGeneration = 9,
                currentGeneration = 9,
                expectedRevision = 17,
                currentRevision = 18
            )
        )
    }

    @Test
    fun reconnectResumesByStoredKeyAndSendsToNewRuntimeSession() {
        val created = sessionHandleForCreate(
            runtimeSessionId = "runtime-before-disconnect",
            storedSessionId = "persistent-session-key"
        )
        assertEquals("persistent-session-key", created.storedSessionId)

        val resumed = sessionHandleForResume(
            requestedStoredSessionId = created.storedSessionId,
            returnedRuntimeSessionId = "runtime-after-reconnect",
            returnedStoredSessionId = "persistent-session-key"
        )

        assertEquals("persistent-session-key", resumed.storedSessionId)
        assertEquals("runtime-after-reconnect", resumed.runtimeSessionId)
    }

    @Test
    fun deferredResponseBeforeCurrentCompletionWaitsForThatBoundary() {
        val pending = deferredSwitchForGatewayResponse(
            runtimeSessionId = "runtime-a",
            provider = "nous",
            model = "openai/gpt-5.6-luna",
            confirmExpensiveModel = false,
            completionEpochAtRequest = 7,
            currentCompletionEpoch = 7
        )

        assertTrue(pending.awaitingCurrentTurnCompletion)
        val afterCurrentTurn = markDeferredSwitchCompletion(pending, "runtime-a")
        assertFalse(afterCurrentTurn.awaitingCurrentTurnCompletion)
        assertFalse(shouldReconcileDeferredSwitch(afterCurrentTurn, "runtime-a"))
    }

    @Test
    fun deferredSwitchReconcilesOnlyAfterAFollowingPromptCompletes() {
        val pending = deferredSwitchForGatewayResponse(
            runtimeSessionId = "runtime-a",
            provider = "nous",
            model = "openai/gpt-5.6-luna",
            confirmExpensiveModel = false,
            completionEpochAtRequest = 7,
            currentCompletionEpoch = 8
        )

        assertFalse(pending.awaitingCurrentTurnCompletion)
        val afterPromptSubmit = markDeferredSwitchPromptSubmitted(pending, "runtime-a")
        assertTrue(shouldReconcileDeferredSwitch(afterPromptSubmit, "runtime-a"))
    }

    @Test
    fun queuedUnconfirmedSwitchRequiresAuthoritativeReconciliationInsteadOfAutomaticRetry() {
        val pending = markDeferredSwitchPromptSubmitted(
            markDeferredSwitchCompletion(
                deferredSwitchForGatewayResponse(
                    runtimeSessionId = "runtime-a",
                    provider = "custom-alias",
                    model = "openai/gpt-5.6-luna",
                    confirmExpensiveModel = false,
                    completionEpochAtRequest = 4,
                    currentCompletionEpoch = 4
                ),
                "runtime-a"
            ),
            "runtime-a"
        )

        assertEquals(
            DeferredErrorAction.ReconcileAuthoritatively,
            deferredErrorAction(pending, "runtime-a")
        )
    }

    @Test
    fun reconnectWithAnIdleServerCompletesTheDeferredTurnBoundary() {
        val pending = deferredSwitchForGatewayResponse(
            runtimeSessionId = "runtime-before-reconnect",
            provider = "nous",
            model = "openai/gpt-5.6-luna",
            confirmExpensiveModel = false,
            completionEpochAtRequest = 3,
            currentCompletionEpoch = 3
        )

        assertFalse(
            reconcileDeferredBoundaryAfterResume(
                pending = pending,
                activeRuntimeSessionId = "runtime-after-reconnect",
                serverRunning = false
            ).awaitingCurrentTurnCompletion
        )
    }

    @Test
    fun idleReconnectResolvesAnAppliedDeferredNextTurn() {
        val pending = markDeferredSwitchPromptSubmitted(
            deferredSwitchForGatewayResponse(
                runtimeSessionId = "runtime-a",
                provider = "nous",
                model = "openai/gpt-5.6-luna",
                confirmExpensiveModel = false,
                completionEpochAtRequest = 5,
                currentCompletionEpoch = 6
            ),
            "runtime-a"
        )

        assertEquals(
            DeferredResumeReconciliation.Applied,
            deferredResumeReconciliation(
                pending = pending,
                serverRunning = false,
                actualProvider = "nous",
                actualModel = "openai/gpt-5.6-luna"
            )
        )
    }

    @Test
    fun idleReconnectReportsAnUnappliedDeferredNextTurn() {
        val pending = markDeferredSwitchPromptSubmitted(
            deferredSwitchForGatewayResponse(
                runtimeSessionId = "runtime-a",
                provider = "nous",
                model = "openai/gpt-5.6-luna",
                confirmExpensiveModel = false,
                completionEpochAtRequest = 5,
                currentCompletionEpoch = 6
            ),
            "runtime-a"
        )

        assertEquals(
            DeferredResumeReconciliation.Failed,
            deferredResumeReconciliation(
                pending = pending,
                serverRunning = false,
                actualProvider = "nous",
                actualModel = "different-model"
            )
        )
    }

    @Test
    fun busyChatCanOpenTheModelPickerButCannotSubmitAnotherPrompt() {
        val actions = chatActionAvailability(
            thinking = true,
            streaming = true,
            modelSwitchInFlight = false,
            awaitingDeferredTurnBoundary = false,
            awaitingDeferredModelResolution = false
        )

        assertFalse(actions.canSubmitPrompt)
        assertTrue(actions.canSwitchModel)
    }

    @Test
    fun modelOptionsResultCannotOverwriteNewerSameSessionModelState() {
        assertFalse(
            shouldApplyModelOptionsResult(
                expectedRuntimeSessionId = "runtime-a",
                activeRuntimeSessionId = "runtime-a",
                expectedGeneration = 7,
                currentGeneration = 7,
                expectedModelStateRevision = 12,
                currentModelStateRevision = 13
            )
        )
        assertTrue(
            shouldApplyModelOptionsResult(
                expectedRuntimeSessionId = "runtime-a",
                activeRuntimeSessionId = "runtime-a",
                expectedGeneration = 7,
                currentGeneration = 7,
                expectedModelStateRevision = 13,
                currentModelStateRevision = 13
            )
        )
    }

    @Test
    fun delayedModelSwitchResultCannotOverwriteNewerSameSessionStateOrRequest() {
        assertFalse(
            shouldApplyModelSwitchResult(
                expectedRuntimeSessionId = "runtime-a",
                activeRuntimeSessionId = "runtime-a",
                expectedGeneration = 7,
                currentGeneration = 7,
                expectedModelStateRevision = 12,
                currentModelStateRevision = 13,
                expectedRequestEpoch = 4,
                currentRequestEpoch = 4
            )
        )
        assertFalse(
            shouldApplyModelSwitchResult(
                expectedRuntimeSessionId = "runtime-a",
                activeRuntimeSessionId = "runtime-a",
                expectedGeneration = 7,
                currentGeneration = 7,
                expectedModelStateRevision = 13,
                currentModelStateRevision = 13,
                expectedRequestEpoch = 4,
                currentRequestEpoch = 5
            )
        )
        assertTrue(
            shouldApplyModelSwitchResult(
                expectedRuntimeSessionId = "runtime-a",
                activeRuntimeSessionId = "runtime-a",
                expectedGeneration = 7,
                currentGeneration = 7,
                expectedModelStateRevision = 13,
                currentModelStateRevision = 13,
                expectedRequestEpoch = 5,
                currentRequestEpoch = 5
            )
        )
    }

    @Test
    fun deferredErrorRequestsAuthoritativeReconciliationInsteadOfConfirmation() {
        val pending = markDeferredSwitchPromptSubmitted(
            markDeferredSwitchCompletion(
                deferredSwitchForGatewayResponse(
                    runtimeSessionId = "runtime-a",
                    provider = "nous",
                    model = "openai/gpt-5.6-luna",
                    confirmExpensiveModel = false,
                    completionEpochAtRequest = 2,
                    currentCompletionEpoch = 2
                ),
                "runtime-a"
            ),
            "runtime-a"
        )

        assertEquals(
            DeferredErrorAction.ReconcileAuthoritatively,
            deferredErrorAction(pending, "runtime-a")
        )
    }

    @Test
    fun bareGatewayErrorRequiresAuthoritativeIdleResumeBeforeReleasingBoundary() {
        val pending = deferredSwitchForGatewayResponse(
            runtimeSessionId = "runtime-a",
            provider = "nous",
            model = "openai/gpt-5.6-luna",
            confirmExpensiveModel = false,
            completionEpochAtRequest = 4,
            currentCompletionEpoch = 4
        )

        assertEquals(
            DeferredErrorAction.ReconcileAuthoritatively,
            deferredErrorAction(pending, "runtime-a")
        )
        assertTrue(
            reconcileDeferredBoundaryAfterResume(
                pending = pending,
                activeRuntimeSessionId = "runtime-after-error",
                serverRunning = true
            ).awaitingCurrentTurnCompletion
        )
        assertFalse(
            reconcileDeferredBoundaryAfterResume(
                pending = pending,
                activeRuntimeSessionId = "runtime-after-error",
                serverRunning = false
            ).awaitingCurrentTurnCompletion
        )
    }

    @Test
    fun outboxRetainsPromptUntilGatewayAcknowledgesIt() {
        val outbox = SessionOutbox()
        outbox.enqueue("stored-a", "prompt-a")

        val pending = outbox.nextFor("stored-a")
        assertEquals("prompt-a", pending?.text)
        assertEquals(1, outbox.size)

        assertTrue(outbox.acknowledge(pending!!))
        assertEquals(0, outbox.size)
    }

    @Test
    fun explicitGatewayRejectionDoesNotDropOutboxEntry() {
        val outbox = SessionOutbox()
        outbox.enqueue("stored-a", "prompt-a")
        val pending = outbox.nextFor("stored-a")!!

        assertTrue(outbox.reject(pending))
        assertEquals(1, outbox.size)
    }

    @Test
    fun authoritativeContinuationRebindMovesOnlyThatDurableSessionsOutbox() {
        val outbox = SessionOutbox()
        outbox.enqueue("stored-parent", "parent prompt")
        outbox.enqueue("stored-other", "other prompt")

        assertEquals(1, outbox.rebindStoredSession("stored-parent", "stored-successor"))
        assertEquals("parent prompt", outbox.nextFor("stored-successor")?.text)
        assertEquals("other prompt", outbox.nextFor("stored-other")?.text)
        assertTrue(outbox.nextFor("stored-parent") == null)
    }

    @Test
    fun persistedOutboxSnapshotSurvivesProcessReconstruction() {
        val beforeProcessDeath = SessionOutbox().apply {
            enqueue("stored-a", "durable prompt")
        }

        val restored = SessionOutbox(beforeProcessDeath.snapshot())
        assertEquals("durable prompt", restored.nextFor("stored-a")?.text)
        assertEquals(1, restored.size)
    }

    @Test
    fun persistedOutboxCodecPreservesIdentityBindingAndAmbiguousDeliveryState() {
        val original = listOf(
            QueuedPrompt(
                id = "prompt-1",
                storedSessionId = "stored-a",
                text = "durable prompt",
                deliveryState = QueuedPromptDeliveryState.Indeterminate
            )
        )

        assertEquals(
            original,
            QueuedPromptSnapshotCodec.decode(QueuedPromptSnapshotCodec.encode(original))
        )
    }

    @Test
    fun sessionOutboxNeverReturnsAnotherSessionsPromptAndRetainsUnacknowledgedPrompt() {
        val outbox = SessionOutbox()
        outbox.enqueue("stored-a", "prompt-a")
        outbox.enqueue("stored-b", "prompt-b")

        val promptForB = outbox.nextFor("stored-b")
        assertEquals("prompt-b", promptForB?.text)
        assertTrue(outbox.acknowledge(promptForB!!))
        assertEquals(1, outbox.size)

        val promptForA = outbox.nextFor("stored-a")
        assertEquals("prompt-a", promptForA?.text)
        // A failed/unknown transport result has no gateway acknowledgement.
        assertEquals(1, outbox.size)
    }

    @Test
    fun outboxFlushWaitsForDeferredResolutionAndResumesAfterItClears() {
        assertFalse(canFlushSessionOutbox(
            awaitingDeferredTurnBoundary = false,
            awaitingDeferredModelResolution = true
        ))
        assertTrue(canFlushSessionOutbox(
            awaitingDeferredTurnBoundary = false,
            awaitingDeferredModelResolution = false
        ))
    }
}
