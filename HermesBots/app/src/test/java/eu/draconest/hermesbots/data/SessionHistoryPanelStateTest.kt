package eu.draconest.hermesbots.data

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryPanelStateTest {
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
    fun historyPanelStaysOpenWhenActiveHistoryIsEmpty() = runTest(dispatcher) {
        val client = HistoryGateway()
        val viewModel = AppViewModel(client)
        viewModel.activeBot.value = BOT

        viewModel.openSessionHistory()
        advanceUntilIdle()

        assertTrue(viewModel.viewSessionHistory.value)
        assertTrue(viewModel.sessionHistoryEntries.value.isEmpty())
    }

    private class HistoryGateway : GatewayClient() {
        override suspend fun listStoredSessions(profile: String, archived: Boolean): List<SessionInfo> = emptyList()
    }

    private companion object {
        val BOT = BotInfo(
            name = "bot-a",
            model = null,
            skillCount = 0,
            gatewayRunning = true
        )
    }
}
