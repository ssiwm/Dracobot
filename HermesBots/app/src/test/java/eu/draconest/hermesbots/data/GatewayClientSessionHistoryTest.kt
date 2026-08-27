package eu.draconest.hermesbots.data

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class GatewayClientSessionHistoryTest {
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
    fun archiveMutationUsesProfileScopedPatchAndEncodesDurableId() = runBlocking {
        var mutation: RecordedRequest? = null
        val webSocket = AtomicReference<WebSocket?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/" -> MockResponse().setBody(
                    "<script>window.__HERMES_SESSION_TOKEN__ = \"test-token\"</script>"
                )
                request.path?.startsWith("/api/ws?token=test-token") == true ->
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(socket: WebSocket, response: okhttp3.Response) {
                            webSocket.set(socket)
                        }
                    })
                request.method == "PATCH" -> {
                    mutation = request
                    webSocket.get()?.close(1000, "test complete")
                    MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"archived\":true}")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = GatewayClient()
        try {
            client.connect(server.url("/").toString().removeSuffix("/"))
            client.updateStoredSession(
                profile = "bot alpha",
                storedSessionId = "stored/with slash",
                archived = true
            )

            val request = requireNotNull(mutation) { "Missing PATCH request" }
            val body = JSONObject(request.body.readUtf8())
            assertEquals("/api/sessions/stored%2Fwith%20slash", request.requestUrl!!.encodedPath)
            assertEquals("test-token", request.getHeader("X-Hermes-Session-Token"))
            assertEquals("bot alpha", body.getString("profile"))
            assertEquals(true, body.getBoolean("archived"))
            assertFalse(body.has("token"))
        } finally {
            client.close()
        }
    }
    @Test
    fun archivedHistoryListsOnlyArchivedEntriesWithinProfile() = runBlocking {
        var listing: RecordedRequest? = null
        val webSocket = AtomicReference<WebSocket?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/" -> MockResponse().setBody(
                    "<script>window.__HERMES_SESSION_TOKEN__ = \"test-token\"</script>"
                )
                request.path?.startsWith("/api/ws?token=test-token") == true ->
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(socket: WebSocket, response: okhttp3.Response) {
                            webSocket.set(socket)
                        }
                    })
                request.method == "GET" && request.requestUrl?.encodedPath == "/api/sessions" -> {
                    listing = request
                    webSocket.get()?.close(1000, "test complete")
                    MockResponse().setResponseCode(200).setBody(
                        """{"sessions":[{"id":"stored-archived","title":"Archiwalna","preview":"nie jest przeszukiwany","message_count":2,"started_at":10,"last_active":20,"archived":true,"is_active":false}]}"""
                    )
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = GatewayClient()
        try {
            client.connect(server.url("/").toString().removeSuffix("/"))
            val entries = client.listStoredSessions(profile = "bot alpha", archived = true)

            val request = requireNotNull(listing) { "Missing history GET request" }
            assertEquals("only", request.requestUrl!!.queryParameter("archived"))
            assertEquals("bot alpha", request.requestUrl!!.queryParameter("profile"))
            assertEquals("tool,kanban", request.requestUrl!!.queryParameter("exclude_sources"))
            assertEquals(1, entries.size)
            assertEquals("stored-archived", entries.single().id)
            assertEquals(true, entries.single().archived)
            assertFalse(entries.single().isActive)
        } finally {
            client.close()
        }
    }
    @Test
    fun deleteMutationUsesProfileQueryAndEncodesDurableId() = runBlocking {
        var deletion: RecordedRequest? = null
        val webSocket = AtomicReference<WebSocket?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/" -> MockResponse().setBody(
                    "<script>window.__HERMES_SESSION_TOKEN__ = \"test-token\"</script>"
                )
                request.path?.startsWith("/api/ws?token=test-token") == true ->
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(socket: WebSocket, response: okhttp3.Response) {
                            webSocket.set(socket)
                        }
                    })
                request.method == "DELETE" -> {
                    deletion = request
                    webSocket.get()?.close(1000, "test complete")
                    MockResponse().setResponseCode(200).setBody("{\"ok\":true}")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val client = GatewayClient()
        try {
            client.connect(server.url("/").toString().removeSuffix("/"))
            client.deleteStoredSession(profile = "bot alpha", storedSessionId = "stored/delete")

            val request = requireNotNull(deletion) { "Missing DELETE request" }
            assertEquals("/api/sessions/stored%2Fdelete", request.requestUrl!!.encodedPath)
            assertEquals("bot alpha", request.requestUrl!!.queryParameter("profile"))
            assertEquals("test-token", request.getHeader("X-Hermes-Session-Token"))
        } finally {
            client.close()
        }
    }
}
