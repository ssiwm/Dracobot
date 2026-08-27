package eu.draconest.hermesbots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        outbox.enqueue("stored-a", "prompt-a", "bot-a")

        val pending = outbox.nextFor("bot-a", "stored-a")
        assertEquals("prompt-a", pending?.text)
        assertEquals(1, outbox.size)

        assertTrue(outbox.acknowledge(pending!!))
        assertEquals(0, outbox.size)
    }

    @Test
    fun explicitGatewayRejectionDoesNotDropOutboxEntry() {
        val outbox = SessionOutbox()
        outbox.enqueue("stored-a", "prompt-a", "bot-a")
        val pending = outbox.nextFor("bot-a", "stored-a")!!

        assertTrue(outbox.reject(pending))
        assertEquals(1, outbox.size)
    }

    @Test
    fun authoritativeContinuationRebindMovesOnlyThatDurableSessionsOutbox() {
        val outbox = SessionOutbox()
        outbox.enqueue("stored-parent", "parent prompt", "bot-a")
        outbox.enqueue("stored-other", "other prompt", "bot-a")

        assertEquals(1, outbox.rebindStoredSession("bot-a", "stored-parent", "stored-successor"))
        assertEquals("parent prompt", outbox.nextFor("bot-a", "stored-successor")?.text)
        assertEquals("other prompt", outbox.nextFor("bot-a", "stored-other")?.text)
        assertTrue(outbox.nextFor("bot-a", "stored-parent") == null)
    }

    @Test
    fun authoritativeRebindIsLimitedToTheMatchingProfileAndNeverMigratesLegacyEntries() {
        val alpha = QueuedPrompt(
            id = "alpha",
            storedSessionId = "stored-shared",
            profileName = "alpha",
            text = "alpha prompt"
        )
        val beta = QueuedPrompt(
            id = "beta",
            storedSessionId = "stored-shared",
            profileName = "beta",
            text = "beta prompt"
        )
        val legacy = QueuedPrompt(
            id = "legacy",
            storedSessionId = "stored-shared",
            text = "legacy prompt"
        )
        val outbox = SessionOutbox(listOf(alpha, beta, legacy))

        assertEquals(1, outbox.rebindStoredSession("alpha", "stored-shared", "stored-alpha-successor"))
        assertEquals("alpha prompt", outbox.nextFor("alpha", "stored-alpha-successor")?.text)
        assertEquals("beta prompt", outbox.nextFor("beta", "stored-shared")?.text)
        assertNull(outbox.nextFor("alpha", "stored-shared"))
        assertEquals("stored-shared", outbox.snapshot().single { it.id == "legacy" }.storedSessionId)
    }

    @Test
    fun legacyUnboundPendingEntryIsNeverAutoSendableForAnyProfile() {
        val legacy = QueuedPrompt(
            id = "legacy",
            storedSessionId = "stored-shared",
            text = "legacy prompt"
        )
        val alpha = QueuedPrompt(
            id = "alpha",
            storedSessionId = "stored-shared",
            profileName = "alpha",
            text = "alpha prompt"
        )
        val outbox = SessionOutbox(listOf(legacy, alpha))

        assertEquals(alpha, outbox.nextFor("alpha", "stored-shared"))
        assertNull(outbox.nextFor("beta", "stored-shared"))
        assertEquals(legacy, outbox.snapshot().single { it.id == "legacy" })
    }

    @Test
    fun persistedOutboxSnapshotSurvivesProcessReconstruction() {
        val beforeProcessDeath = SessionOutbox().apply {
            enqueue("stored-a", "durable prompt", "bot-a")
        }

        val restored = SessionOutbox(beforeProcessDeath.snapshot())
        assertEquals("durable prompt", restored.nextFor("bot-a", "stored-a")?.text)
        assertEquals(1, restored.size)
    }

    @Test
    fun persistedOutboxCodecPreservesIdentityBindingAndAmbiguousDeliveryState() {
        val original = listOf(
            QueuedPrompt(
                id = "prompt-1",
                storedSessionId = "stored-a",
                profileName = "bot-a",
                text = "durable prompt",
                deliveryState = QueuedPromptDeliveryState.Indeterminate,
                deliveryDetail = "Gateway acknowledgement timed out",
                createdAtEpochMillis = 1_234L
            )
        )

        assertEquals(
            original,
            QueuedPromptSnapshotCodec.decode(QueuedPromptSnapshotCodec.encode(original))
        )
    }

    @Test
    fun unknownOrMissingPersistedDeliveryStateIsHeldForExplicitResolution() {
        val unknown = QueuedPromptSnapshotCodec.decode(
            """[{"id":"future","stored_session_id":"stored-a","profile_name":"bot-a","text":"future","delivery_state":"FutureState"}]"""
        ).single()
        val missing = QueuedPromptSnapshotCodec.decode(
            """[{"id":"legacy","stored_session_id":"stored-b","profile_name":"bot-a","text":"legacy"}]"""
        ).single()

        assertEquals(QueuedPromptDeliveryState.Indeterminate, unknown.deliveryState)
        assertEquals(QueuedPromptDeliveryState.Indeterminate, missing.deliveryState)
        assertEquals(0L, missing.createdAtEpochMillis)
    }

    @Test
    fun malformedPersistedEnqueueTimestampIsUnknownInsteadOfCoerced() {
        val fractional = QueuedPromptSnapshotCodec.decode(
            """[{"id":"fractional","stored_session_id":"stored-a","profile_name":"bot-a","text":"x","delivery_state":"Pending","created_at_epoch_ms":1234.5}]"""
        ).single()
        val numericString = QueuedPromptSnapshotCodec.decode(
            """[{"id":"numeric-string","stored_session_id":"stored-b","profile_name":"bot-a","text":"x","delivery_state":"Pending","created_at_epoch_ms":"1234"}]"""
        ).single()
        val nonPositive = QueuedPromptSnapshotCodec.decode(
            """[{"id":"zero","stored_session_id":"stored-c","profile_name":"bot-a","text":"x","delivery_state":"Pending","created_at_epoch_ms":0}]"""
        ).single()

        assertEquals(0L, fractional.createdAtEpochMillis)
        assertEquals(0L, numericString.createdAtEpochMillis)
        assertEquals(0L, nonPositive.createdAtEpochMillis)
    }

    @Test
    fun explicitResendCreatesANewPendingEntryAndKeepsTheProfileBinding() {
        val held = QueuedPrompt(
            id = "held-entry",
            storedSessionId = "stored-a",
            profileName = "bot-a",
            text = "send only after user confirms",
            deliveryState = QueuedPromptDeliveryState.Indeterminate,
            deliveryDetail = "Gateway acknowledgement timed out",
            createdAtEpochMillis = 0L
        )
        val outbox = SessionOutbox(listOf(held))

        val replacement = outbox.requeueAsNew(held)

        assertTrue(replacement != null)
        assertFalse(replacement!!.id == held.id)
        assertEquals("bot-a", replacement.profileName)
        assertEquals(QueuedPromptDeliveryState.Pending, replacement.deliveryState)
        assertNull(replacement.deliveryDetail)
        assertTrue(replacement.createdAtEpochMillis > 0L)
        assertEquals(replacement, outbox.nextFor("bot-a", "stored-a"))
        assertEquals(1, outbox.size)
    }

    @Test
    fun pendingEntryCannotBeRequeuedAsNewWithoutAResolutionState() {
        val pending = QueuedPrompt(
            id = "pending-entry",
            storedSessionId = "stored-a",
            profileName = "bot-a",
            text = "still eligible for automatic delivery"
        )
        val outbox = SessionOutbox(listOf(pending))

        assertNull(outbox.requeueAsNew(pending))
        assertEquals(pending, outbox.nextFor("bot-a", "stored-a"))
    }

    @Test
    fun sessionOutboxNeverReturnsAnotherSessionsPromptAndRetainsUnacknowledgedPrompt() {
        val outbox = SessionOutbox()
        outbox.enqueue("stored-a", "prompt-a", "bot-a")
        outbox.enqueue("stored-b", "prompt-b", "bot-a")

        val promptForB = outbox.nextFor("bot-a", "stored-b")
        assertEquals("prompt-b", promptForB?.text)
        assertTrue(outbox.acknowledge(promptForB!!))
        assertEquals(1, outbox.size)

        val promptForA = outbox.nextFor("bot-a", "stored-a")
        assertEquals("prompt-a", promptForA?.text)
        // A failed/unknown transport result has no gateway acknowledgement.
        assertEquals(1, outbox.size)
    }

    @Test
    fun acknowledgedOutboxEntryCannotBeClaimedForADelayedSecondSubmission() {
        val outbox = SessionOutbox()
        val entry = outbox.enqueue("stored-a", "prompt-a", "bot-a")

        assertTrue(canSubmitOutboxEntry(outbox.entryById(entry.id), entry))
        assertTrue(outbox.acknowledge(entry))
        assertFalse(canSubmitOutboxEntry(outbox.entryById(entry.id), entry))
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
