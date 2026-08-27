package eu.draconest.hermesbots.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkBannerStateTest {
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
    fun restoredLinkClearsOfflineBannerWithoutReconnectingAgain() = runTest(dispatcher) {
        val client = LinkGateway()
        val viewModel = AppViewModel(client)
        viewModel.connected.value = true
        viewModel.observeLink()
        runCurrent()

        client.setLink(LinkState.DOWN)
        runCurrent()
        assertTrue(viewModel.offline.value)

        client.setLink(LinkState.UP)
        runCurrent()

        assertFalse(viewModel.offline.value)
        assertTrue(client.connectCalls == 0)
    }

    @Test
    fun successfulResumeHealthCheckClearsOfflineBanner() = runTest(dispatcher) {
        val client = LinkGateway()
        val viewModel = AppViewModel(client)
        viewModel.initializeStore(testStore())
        viewModel.connected.value = true
        viewModel.offline.value = true

        viewModel.onNetworkMaybeRestored()
        advanceUntilIdle()

        assertFalse(viewModel.offline.value)
    }

    @Test
    fun successfulConnectClearsOfflineBanner() = runTest(dispatcher) {
        val client = LinkGateway()
        val viewModel = AppViewModel(client)
        viewModel.offline.value = true

        viewModel.connect("https://test.invalid", "", "")
        advanceUntilIdle()

        assertTrue(viewModel.connected.value)
        assertFalse(viewModel.offline.value)
    }

    @Test
    fun retryConnectClearsOfflineBannerAfterInitialFailure() = runTest(dispatcher) {
        val client = LinkGateway().apply { remainingConnectFailures = 1 }
        val viewModel = AppViewModel(client)
        viewModel.initializeStore(testStore())
        viewModel.offline.value = true

        viewModel.connect("https://test.invalid", "", "")
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(viewModel.connected.value)
        assertFalse(viewModel.offline.value)
    }

    @Test
    fun failedResumeHealthCheckInvalidatesTransportBeforeReconnect() = runTest(dispatcher) {
        val client = LinkGateway().apply { failProfiles = true }
        val viewModel = AppViewModel(client)
        viewModel.initializeStore(testStore())
        viewModel.connected.value = true

        viewModel.onNetworkMaybeRestored()
        runCurrent()

        assertTrue(viewModel.offline.value)
        assertTrue(client.invalidationCalls == 1)
    }

    private fun testStore(): AppStore {
        val context = RuntimeEnvironment.getApplication()
        return AppStore(
            context,
            context.getSharedPreferences("network-banner-test", Context.MODE_PRIVATE).also { prefs ->
                prefs.edit().clear().putString("username", "u").putString("password", "p").apply()
            }
        )
    }

    private class LinkGateway : GatewayClient() {
        private val mutableLinkState = MutableStateFlow(LinkState.UP)
        override val linkState: StateFlow<LinkState> = mutableLinkState
        var connectCalls = 0
        var remainingConnectFailures = 0
        var invalidationCalls = 0
        var failProfiles = false

        override suspend fun listProfiles(): List<BotInfo> {
            if (failProfiles) error("synthetic health failure")
            return emptyList()
        }

        override fun invalidateTransport() {
            invalidationCalls += 1
        }

        fun setLink(state: LinkState) {
            mutableLinkState.value = state
        }

        override suspend fun connect(url: String, username: String, password: String) {
            connectCalls += 1
            if (remainingConnectFailures > 0) {
                remainingConnectFailures -= 1
                error("synthetic connect failure")
            }
        }
    }
}
