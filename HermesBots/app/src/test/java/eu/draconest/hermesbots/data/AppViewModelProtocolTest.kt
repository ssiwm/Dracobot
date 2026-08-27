package eu.draconest.hermesbots.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelProtocolTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun bareErrorIdleResumeRebindsOutboxAndDrainsOnlyAfterAuthoritativeIdle() = runTest(dispatcher) {
        val fake = FakeGatewayClient()
        val viewModel = openResumedChat(fake)

        fake.nextPromptResult = PromptSubmissionResult.NotSent
        viewModel.send("queued before continuation")
        advanceUntilIdle()
        assertEquals(
            listOf(PromptCall("runtime-a", "queued before continuation", queued = false)),
            fake.promptCalls
        )

        fake.nextPromptResult = PromptSubmissionResult.Accepted("streaming")
        fake.setModelHandler = { _, _, _, _, _ -> ModelSwitchResult.Deferred }
        assertEquals(
            ModelSwitchResult.Deferred,
            viewModel.switchModel("nous", "openai/gpt-5.6-luna")
        )
        assertTrue(viewModel.awaitingDeferredTurnBoundary.value)

        fake.resumed = fake.resumedSession(
            runtimeSessionId = "runtime-b",
            storedSessionId = "stored-b",
            isRunning = false
        )
        fake.emit(
            JSONObject()
                .put("type", "error")
                .put("session_id", "runtime-a")
                .put("payload", JSONObject().put("message", "turn ended before ready"))
        )
        advanceUntilIdle()

        assertFalse(viewModel.awaitingDeferredTurnBoundary.value)
        assertTrue(viewModel.awaitingDeferredModelResolution.value)
        assertEquals(2, fake.promptCalls.size)
        assertEquals("runtime-b", fake.promptCalls.last().runtimeSessionId)
        assertEquals("queued before continuation", fake.promptCalls.last().text)
        assertTrue(fake.promptCalls.last().queued)
    }

    @Test
    fun bareErrorKeepsDeferredBoundaryWhenAuthoritativeResumeReportsRunning() = runTest(dispatcher) {
        val fake = FakeGatewayClient()
        val viewModel = openResumedChat(fake)
        fake.setModelHandler = { _, _, _, _, _ -> ModelSwitchResult.Deferred }
        assertEquals(
            ModelSwitchResult.Deferred,
            viewModel.switchModel("nous", "openai/gpt-5.6-luna")
        )

        fake.resumed = fake.resumedSession(
            runtimeSessionId = "runtime-b",
            storedSessionId = "stored-b",
            isRunning = true
        )
        fake.emit(
            JSONObject()
                .put("type", "error")
                .put("session_id", "runtime-a")
                .put("payload", JSONObject().put("message", "still running remotely"))
        )
        advanceUntilIdle()

        assertTrue(viewModel.awaitingDeferredTurnBoundary.value)
        assertTrue(viewModel.thinking.value)
        assertTrue(fake.promptCalls.isEmpty())
    }

    @Test
    fun modelSwitchTimeoutUnblocksViewModelWithoutRetryingConfigSet() = runTest(dispatcher) {
        val fake = FakeGatewayClient().apply {
            setModelHandler = { _, _, _, _, _ -> awaitCancellation() }
        }
        val viewModel = openResumedChat(fake, modelSwitchTimeoutMs = 50)

        assertEquals(
            ModelSwitchResult.TimedOut,
            viewModel.switchModel("nous", "openai/gpt-5.6-luna")
        )
        assertFalse(viewModel.modelSwitchInFlight.value)
        assertEquals(1, fake.modelSwitchCalls)
    }

    @Test
    fun lateSameSessionConfigResultCannotOverwriteNewerSessionInfo() = runTest(dispatcher) {
        val reply = CompletableDeferred<ModelSwitchResult>()
        val fake = FakeGatewayClient().apply {
            setModelHandler = { _, _, _, _, _ -> reply.await() }
        }
        val viewModel = openResumedChat(fake)

        val switch = async {
            viewModel.switchModel("nous", "openai/gpt-5.6-luna")
        }
        runCurrent()
        fake.emit(
            JSONObject()
                .put("type", "session.info")
                .put("session_id", "runtime-a")
                .put("payload", JSONObject()
                    .put("provider", "authoritative-provider")
                    .put("model", "authoritative/model"))
        )
        runCurrent()
        reply.complete(ModelSwitchResult.Applied)

        assertEquals(
            ModelSwitchResult.Failure("Zmiana modelu została zastąpiona nowszym stanem sesji."),
            switch.await()
        )
        assertEquals("authoritative-provider", viewModel.currentProvider.value)
        assertEquals("authoritative/model", viewModel.currentModel.value)
        assertFalse(viewModel.modelSwitchInFlight.value)
    }

    @Test
    fun directPromptCannotOvertakeAnOlderPendingOutboxEntryForTheSameSession() = runTest(dispatcher) {
        val fake = FakeGatewayClient()
        val viewModel = openResumedChat(fake)

        fake.promptResults += PromptSubmissionResult.NotSent
        viewModel.send("first")
        advanceUntilIdle()

        fake.promptResults += PromptSubmissionResult.Accepted("queued")
        viewModel.send("second")
        advanceUntilIdle()

        assertEquals(
            listOf(
                PromptCall("runtime-a", "first", queued = false),
                PromptCall("runtime-a", "first", queued = true)
            ),
            fake.promptCalls
        )
    }

    @Test
    fun regenerateCannotOvertakeAnOlderPendingOutboxEntryForTheSameSession() = runTest(dispatcher) {
        val fake = FakeGatewayClient()
        val viewModel = openResumedChat(fake)
        viewModel.messages.value = listOf(
            ChatMessage(1, fromUser = true, text = "original task"),
            ChatMessage(2, fromUser = false, text = "original answer")
        )

        fake.promptResults += PromptSubmissionResult.NotSent
        viewModel.send("first")
        advanceUntilIdle()

        fake.promptResults += PromptSubmissionResult.Accepted("queued")
        viewModel.regenerateLast()
        advanceUntilIdle()

        assertEquals(
            listOf(
                PromptCall("runtime-a", "first", queued = false),
                PromptCall("runtime-a", "first", queued = true)
            ),
            fake.promptCalls
        )
    }

    @Test
    fun directPromptCannotOvertakeAnOlderRejectedOutboxEntryForTheSameSession() = runTest(dispatcher) {
        val fake = FakeGatewayClient()
        val viewModel = openResumedChat(fake)

        fake.promptResults += PromptSubmissionResult.Rejected(code = 4001, message = "Gateway rejected first")
        viewModel.send("first")
        advanceUntilIdle()

        viewModel.send("second")
        advanceUntilIdle()

        assertEquals(
            listOf(PromptCall("runtime-a", "first", queued = false)),
            fake.promptCalls
        )
    }

    @Test
    fun regenerateCannotOvertakeAnOlderIndeterminateOutboxEntryForTheSameSession() = runTest(dispatcher) {
        val fake = FakeGatewayClient()
        val viewModel = openResumedChat(fake)
        viewModel.messages.value = listOf(
            ChatMessage(1, fromUser = true, text = "original task"),
            ChatMessage(2, fromUser = false, text = "original answer")
        )

        fake.promptResults += PromptSubmissionResult.Indeterminate
        viewModel.send("first")
        advanceUntilIdle()

        viewModel.regenerateLast()
        advanceUntilIdle()

        assertEquals(
            listOf(PromptCall("runtime-a", "first", queued = false)),
            fake.promptCalls
        )
    }

    @Test
    fun reconnectDrainDoesNotAutoReplayRejectedHeadOrSendLaterPrompt() = runTest(dispatcher) {
        val fake = FakeGatewayClient()
        val viewModel = openResumedChat(fake)

        fake.promptResults += PromptSubmissionResult.Rejected(code = 4001, message = "Gateway rejected first")
        viewModel.send("first")
        advanceUntilIdle()
        viewModel.send("second")
        advanceUntilIdle()

        viewModel.flushOutbox()
        advanceUntilIdle()

        assertEquals(
            listOf(PromptCall("runtime-a", "first", queued = false)),
            fake.promptCalls
        )
    }

    @Test
    fun reconnectDrainDoesNotAutoReplayIndeterminateHeadOrSendLaterPrompt() = runTest(dispatcher) {
        val fake = FakeGatewayClient()
        val viewModel = openResumedChat(fake)

        fake.promptResults += PromptSubmissionResult.Indeterminate
        viewModel.send("first")
        advanceUntilIdle()
        viewModel.send("second")
        advanceUntilIdle()

        viewModel.flushOutbox()
        advanceUntilIdle()

        assertEquals(
            listOf(PromptCall("runtime-a", "first", queued = false)),
            fake.promptCalls
        )
    }

    @Test
    fun outboxCenterExplicitResendCreatesFreshQueuedAttemptForTheActiveDurableSession() = runTest(dispatcher) {
        val fake = FakeGatewayClient()
        val viewModel = openResumedChat(fake)

        fake.promptResults += PromptSubmissionResult.Rejected(code = 4001, message = "Gateway rejected first")
        viewModel.send("first")
        advanceUntilIdle()
        val held = viewModel.outboxEntries.value.single()
        assertEquals(QueuedPromptDeliveryState.Rejected, held.deliveryState)
        assertEquals("Gateway rejected first", held.deliveryDetail)

        fake.promptResults += PromptSubmissionResult.Accepted("queued")
        viewModel.resendOutboxEntryAsNew(held.id)
        advanceUntilIdle()

        assertEquals(
            listOf(
                PromptCall("runtime-a", "first", queued = false),
                PromptCall("runtime-a", "first", queued = true)
            ),
            fake.promptCalls
        )
        assertTrue(viewModel.outboxEntries.value.isEmpty())
    }

    @Test
    fun outboxCenterDiscardRemovesHeldEntryWithoutAnyAdditionalTransportAttempt() = runTest(dispatcher) {
        val fake = FakeGatewayClient()
        val viewModel = openResumedChat(fake)

        fake.promptResults += PromptSubmissionResult.Indeterminate
        viewModel.send("first")
        advanceUntilIdle()
        val held = viewModel.outboxEntries.value.single()

        viewModel.discardOutboxEntry(held.id)
        viewModel.flushOutbox()
        advanceUntilIdle()

        assertEquals(listOf(PromptCall("runtime-a", "first", queued = false)), fake.promptCalls)
        assertTrue(viewModel.outboxEntries.value.isEmpty())
    }

    @Test
    fun notificationTargetResumesItsExactProfileAndDurableSession() = runTest(dispatcher) {
        val fake = FakeGatewayClient()
        val viewModel = AppViewModel(fake)
        viewModel.connect("http://localhost", "", "")
        advanceUntilIdle()

        assertTrue(viewModel.openNotificationTarget(NotificationTarget("bot-a", "stored-notification")))
        advanceUntilIdle()

        assertEquals(listOf("bot-a" to "stored-notification"), fake.resumeCalls)
        assertEquals(BOT, viewModel.activeBot.value)
    }

    private suspend fun TestScope.openResumedChat(
        fake: FakeGatewayClient,
        modelSwitchTimeoutMs: Long = 12_000L
    ): AppViewModel {
        val viewModel = AppViewModel(fake, modelSwitchTimeoutMs)
        viewModel.connect("http://localhost", "", "")
        advanceUntilIdle()
        viewModel.activeBot.value = BOT
        viewModel.resumeChat(SESSION_INFO)
        advanceUntilIdle()
        return viewModel
    }

    private data class PromptCall(
        val runtimeSessionId: String,
        val text: String,
        val queued: Boolean
    )

    private class FakeGatewayClient : GatewayClient() {
        private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 16)
        override val events: SharedFlow<JSONObject> = _events.asSharedFlow()
        private val _linkState = MutableStateFlow(LinkState.UP)
        override val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

        var resumed = resumedSession("runtime-a", "stored-a", isRunning = false)
        var nextPromptResult: PromptSubmissionResult = PromptSubmissionResult.Accepted("streaming")
        val promptResults = ArrayDeque<PromptSubmissionResult>()
        val promptCalls = mutableListOf<PromptCall>()
        val resumeCalls = mutableListOf<Pair<String, String>>()
        var modelSwitchCalls = 0
        var setModelHandler: suspend (String, String, String, Boolean, Long) -> ModelSwitchResult =
            { _, _, _, _, _ -> ModelSwitchResult.Applied }

        override suspend fun connect(url: String, username: String, password: String) = Unit

        override suspend fun listProfiles(): List<BotInfo> = listOf(BOT)

        override suspend fun rosterSummary(profile: String): RosterSummary? = null

        override suspend fun resumeSession(profile: String, sessionId: String): ResumedSession {
            resumeCalls += profile to sessionId
            return resumed
        }

        override suspend fun modelOptions(sessionId: String?): ModelOptionsPayload = ModelOptionsPayload(
            providers = emptyList(),
            model = resumed.model,
            provider = resumed.provider
        )

        override suspend fun setSessionModel(
            sessionId: String,
            provider: String,
            model: String,
            confirmExpensiveModel: Boolean,
            timeoutMs: Long
        ): ModelSwitchResult {
            modelSwitchCalls += 1
            return setModelHandler(sessionId, provider, model, confirmExpensiveModel, timeoutMs)
        }

        internal override suspend fun submitPromptResult(
            sessionId: String,
            text: String,
            queued: Boolean,
            acknowledgementTimeoutMs: Long
        ): PromptSubmissionResult {
            promptCalls += PromptCall(sessionId, text, queued)
            return promptResults.removeFirstOrNull() ?: nextPromptResult
        }

        suspend fun emit(event: JSONObject) {
            _events.emit(event)
        }

        fun resumedSession(
            runtimeSessionId: String,
            storedSessionId: String,
            isRunning: Boolean
        ): ResumedSession = ResumedSession(
            handle = SessionHandle(runtimeSessionId, storedSessionId),
            messages = emptyList(),
            model = "initial/model",
            provider = "initial-provider",
            isRunning = isRunning
        )
    }

    private companion object {
        val BOT = BotInfo(
            name = "bot-a",
            model = null,
            skillCount = 0,
            gatewayRunning = true
        )
        val SESSION_INFO = SessionInfo(
            id = "stored-a",
            title = "Test chat",
            preview = "",
            messageCount = 0,
            startedAt = 0L
        )
    }
}
