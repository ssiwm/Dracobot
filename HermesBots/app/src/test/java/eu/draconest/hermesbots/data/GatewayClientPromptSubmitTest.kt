package eu.draconest.hermesbots.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayClientPromptSubmitTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun promptSubmitUsesQueuedContractSoSettleRaceCannotRedirectTheTurn() = runBlocking {
        val receivedPrompt = AtomicReference<JSONObject?>()
        val received = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/" -> MockResponse().setBody(
                    "<script>window.__HERMES_SESSION_TOKEN__ = \"test-token\"</script>"
                )
                request.path?.startsWith("/api/ws?token=test-token") == true ->
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val requestFrame = JSONObject(text)
                            if (requestFrame.optString("method") == "prompt.submit") {
                                receivedPrompt.set(requestFrame.getJSONObject("params"))
                                webSocket.send(
                                    JSONObject()
                                        .put("jsonrpc", "2.0")
                                        .put("id", requestFrame.getInt("id"))
                                        .put("result", JSONObject().put("status", "queued"))
                                        .toString()
                                )
                                received.countDown()
                                webSocket.close(1000, "test complete")
                            }
                        }
                    })
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = GatewayClient()
        client.connect(server.url("/").toString().removeSuffix("/"))

        assertTrue(client.submitPrompt("runtime-session", "next prompt", queued = true))
        assertTrue("Gateway did not receive prompt.submit", received.await(3, TimeUnit.SECONDS))
        assertTrue(receivedPrompt.get()?.optBoolean("queued") == true)
        client.close()
    }

    @Test
    fun normalPromptSubmitDoesNotForceQueuedMode() = runBlocking {
        val receivedPrompt = AtomicReference<JSONObject?>()
        val received = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/" -> MockResponse().setBody(
                    "<script>window.__HERMES_SESSION_TOKEN__ = \"test-token\"</script>"
                )
                request.path?.startsWith("/api/ws?token=test-token") == true ->
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val requestFrame = JSONObject(text)
                            if (requestFrame.optString("method") == "prompt.submit") {
                                receivedPrompt.set(requestFrame.getJSONObject("params"))
                                webSocket.send(
                                    JSONObject()
                                        .put("jsonrpc", "2.0")
                                        .put("id", requestFrame.getInt("id"))
                                        .put("result", JSONObject().put("status", "streaming"))
                                        .toString()
                                )
                                received.countDown()
                                webSocket.close(1000, "test complete")
                            }
                        }
                    })
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = GatewayClient()
        client.connect(server.url("/").toString().removeSuffix("/"))

        assertTrue(client.submitPrompt("runtime-session", "ordinary prompt"))
        assertTrue("Gateway did not receive prompt.submit", received.await(3, TimeUnit.SECONDS))
        assertFalse(receivedPrompt.get()?.has("queued") == true)
        client.close()
    }

    @Test
    fun promptAcknowledgementDeadlineIsIndeterminateRatherThanDelivered() = runBlocking {
        val received = CountDownLatch(1)
        val peerReleased = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/" -> MockResponse().setBody(
                    "<script>window.__HERMES_SESSION_TOKEN__ = \"test-token\"</script>"
                )
                request.path?.startsWith("/api/ws?token=test-token") == true ->
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (JSONObject(text).optString("method") == "prompt.submit") {
                                received.countDown()
                                // Close only after the client's shorter acknowledgement deadline.
                                Thread {
                                    Thread.sleep(250)
                                    webSocket.close(1000, "deadline test complete")
                                    peerReleased.countDown()
                                }.apply {
                                    isDaemon = true
                                    start()
                                }
                            }
                        }
                    })
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = GatewayClient()
        try {
            client.connect(server.url("/").toString().removeSuffix("/"))
            val result = client.submitPromptResult(
                sessionId = "runtime-session",
                text = "may have arrived",
                acknowledgementTimeoutMs = 75
            )
            assertTrue("Gateway did not receive prompt.submit", received.await(3, TimeUnit.SECONDS))
            assertEquals(PromptSubmissionResult.Indeterminate, result)
            assertTrue("Mock peer did not close", peerReleased.await(1, TimeUnit.SECONDS))
        } finally {
            client.close()
        }
    }

    @Test
    fun promptSubmitDoesNotClaimDeliveryWhenGatewayRejectsIt() = runBlocking {
        val rejectionSent = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/" -> MockResponse().setBody(
                    "<script>window.__HERMES_SESSION_TOKEN__ = \"test-token\"</script>"
                )
                request.path?.startsWith("/api/ws?token=test-token") == true ->
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val requestFrame = JSONObject(text)
                            if (requestFrame.optString("method") == "prompt.submit") {
                                webSocket.send(
                                    JSONObject()
                                        .put("jsonrpc", "2.0")
                                        .put("id", requestFrame.getInt("id"))
                                        .put("error", JSONObject()
                                            .put("code", 4009)
                                            .put("message", "session busy"))
                                        .toString()
                                )
                                rejectionSent.countDown()
                                webSocket.close(1000, "test complete")
                            }
                        }
                    })
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = GatewayClient()
        try {
            client.connect(server.url("/").toString().removeSuffix("/"))
            val accepted = client.submitPrompt("runtime-session", "next prompt")
            assertTrue("Gateway did not send the rejection", rejectionSent.await(3, TimeUnit.SECONDS))
            assertFalse(accepted)
        } finally {
            client.close()
        }
    }

    @Test
    fun modelSwitchDeadlineReturnsTimedOutInsteadOfLeavingUiInFlight() = runBlocking {
        val received = CountDownLatch(1)
        val peerReleased = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/" -> MockResponse().setBody(
                    "<script>window.__HERMES_SESSION_TOKEN__ = \"test-token\"</script>"
                )
                request.path?.startsWith("/api/ws?token=test-token") == true ->
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (JSONObject(text).optString("method") == "config.set") {
                                received.countDown()
                                Thread {
                                    Thread.sleep(250)
                                    webSocket.close(1000, "model timeout test complete")
                                    peerReleased.countDown()
                                }.apply {
                                    isDaemon = true
                                    start()
                                }
                            }
                        }
                    })
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = GatewayClient()
        try {
            client.connect(server.url("/").toString().removeSuffix("/"))
            val result = client.setSessionModel(
                sessionId = "runtime-session",
                provider = "nous",
                model = "openai/gpt-5.6-luna",
                timeoutMs = 75
            )
            assertTrue("Gateway did not receive config.set", received.await(3, TimeUnit.SECONDS))
            assertEquals(
                ModelSwitchResult.TimedOut,
                result
            )
            assertTrue("Mock peer did not close", peerReleased.await(1, TimeUnit.SECONDS))
        } finally {
            client.close()
        }
    }
}
