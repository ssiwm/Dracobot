package eu.draconest.hermesbots.data

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupChatEngineContinuationTest {
    @Test
    fun parentToSuccessorContinuationUsesNewestRuntimeForSubmitAndNewestStoredForPolling() = runTest {
        val fake = ContinuationGateway()
        val member = BotInfo(name = "ada", model = null, skillCount = 0, gatewayRunning = true)

        val reply = GroupChatEngine.runMemberTurn(fake, "engineering", member, "status update")

        assertEquals("done", reply)
        assertEquals(listOf("runtime-successor"), fake.submittedRuntimeIds)
        assertEquals(listOf("stored-successor"), fake.polledStoredIds)
    }

    @Test
    fun everyPollingResumeAdvancesTheDurableKeyForTheNextPoll() = runTest {
        val fake = PollingContinuationGateway()
        val member = BotInfo(name = "ada", model = null, skillCount = 0, gatewayRunning = true)

        val reply = GroupChatEngine.runMemberTurn(fake, "engineering", member, "status update")

        assertEquals("done", reply)
        assertEquals(listOf("runtime-a"), fake.submittedRuntimeIds)
        assertEquals(listOf("stored-a", "stored-b"), fake.polledStoredIds)
    }

    @Test
    fun missingListedSessionCreatesThenUsesAuthoritativeResumeHandleForSubmitAndPolling() = runTest {
        val fake = CreateFallbackGateway()
        val member = BotInfo(name = "ada", model = null, skillCount = 0, gatewayRunning = true)

        val reply = GroupChatEngine.runMemberTurn(fake, "engineering", member, "status update")

        assertEquals("done", reply)
        assertEquals(listOf("session.list", "session.create"), fake.rawMethods)
        assertEquals(listOf("created-stored"), fake.resumeRequestedStoredIds)
        assertEquals(listOf("runtime-authoritative"), fake.submittedRuntimeIds)
        assertEquals(listOf("stored-authoritative"), fake.polledStoredIds)
    }

    private class ContinuationGateway : GatewayClient() {
        val submittedRuntimeIds = mutableListOf<String>()
        val polledStoredIds = mutableListOf<String>()

        override suspend fun rpcRaw(method: String, params: JSONObject): JSONObject = when (method) {
            "session.list" -> JSONObject().put("sessions", org.json.JSONArray().put(JSONObject().put("id", "stored-parent")))
            "session.resume" -> JSONObject()
                .put("session_id", "runtime-parent")
                .put("stored_session_id", "stored-successor")
            else -> error("Unexpected raw RPC: $method")
        }

        override suspend fun resumeSession(profile: String, sessionId: String): ResumedSession {
            assertEquals("stored-parent", sessionId)
            return ResumedSession(
                handle = SessionHandle("runtime-successor", "stored-successor"),
                messages = emptyList(),
                model = "",
                provider = "",
                isRunning = false
            )
        }

        override suspend fun submitPrompt(sessionId: String, text: String, queued: Boolean): Boolean {
            submittedRuntimeIds += sessionId
            return true
        }

        override suspend fun resumeSessionState(profile: String, sessionId: String): GroupChatEngine.TurnState {
            polledStoredIds += sessionId
            return GroupChatEngine.TurnState(
                handle = SessionHandle("runtime-successor", "stored-successor"),
                messageCount = 1,
                lastAssistantText = "done",
                inflight = false,
                running = false
            )
        }
    }

    private class PollingContinuationGateway : GatewayClient() {
        val submittedRuntimeIds = mutableListOf<String>()
        val polledStoredIds = mutableListOf<String>()

        override suspend fun rpcRaw(method: String, params: JSONObject): JSONObject = when (method) {
            "session.list" -> JSONObject().put("sessions", org.json.JSONArray().put(JSONObject().put("id", "stored-a")))
            else -> error("Unexpected raw RPC: $method")
        }

        override suspend fun resumeSession(profile: String, sessionId: String) = ResumedSession(
            handle = SessionHandle("runtime-a", "stored-a"),
            messages = emptyList(),
            model = "",
            provider = "",
            isRunning = false
        )

        override suspend fun submitPrompt(sessionId: String, text: String, queued: Boolean): Boolean {
            submittedRuntimeIds += sessionId
            return true
        }

        override suspend fun resumeSessionState(profile: String, sessionId: String): GroupChatEngine.TurnState {
            polledStoredIds += sessionId
            return when (polledStoredIds.size) {
                1 -> GroupChatEngine.TurnState(
                    handle = SessionHandle("runtime-b", "stored-b"),
                    messageCount = 0,
                    lastAssistantText = null,
                    inflight = true,
                    running = true
                )
                2 -> GroupChatEngine.TurnState(
                    handle = SessionHandle("runtime-c", "stored-c"),
                    messageCount = 1,
                    lastAssistantText = "done",
                    inflight = false,
                    running = false
                )
                else -> error("Unexpected extra poll")
            }
        }
    }

    private class CreateFallbackGateway : GatewayClient() {
        val rawMethods = mutableListOf<String>()
        val resumeRequestedStoredIds = mutableListOf<String>()
        val submittedRuntimeIds = mutableListOf<String>()
        val polledStoredIds = mutableListOf<String>()

        override suspend fun rpcRaw(method: String, params: JSONObject): JSONObject {
            rawMethods += method
            return when (method) {
                "session.list" -> JSONObject().put("sessions", org.json.JSONArray())
                "session.create" -> JSONObject().put("stored_session_id", "created-stored")
                else -> error("Unexpected raw RPC: $method")
            }
        }

        override suspend fun resumeSession(profile: String, sessionId: String): ResumedSession {
            resumeRequestedStoredIds += sessionId
            return ResumedSession(
                handle = SessionHandle("runtime-authoritative", "stored-authoritative"),
                messages = emptyList(),
                model = "",
                provider = "",
                isRunning = false
            )
        }

        override suspend fun submitPrompt(sessionId: String, text: String, queued: Boolean): Boolean {
            submittedRuntimeIds += sessionId
            return true
        }

        override suspend fun resumeSessionState(profile: String, sessionId: String): GroupChatEngine.TurnState {
            polledStoredIds += sessionId
            return GroupChatEngine.TurnState(
                handle = SessionHandle("runtime-authoritative", "stored-authoritative"),
                messageCount = 1,
                lastAssistantText = "done",
                inflight = false,
                running = false
            )
        }
    }
}
