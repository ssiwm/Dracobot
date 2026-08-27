package eu.draconest.hermesbots.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Czat grupowy botów — clean-room port logiki z desktopowego Bot Mode:
 * JEDEN log pokoju; wysłanie usera uruchamia maks. MAX_ROUNDS seryjnych rund
 * round-robin. Kto mówi: deterministyczny parse @mentions od ostatniej wiadomości
 * usera (nikt nie wspomniany -> wszyscy). Czy czlonek rzeczywiście się wypowie,
 * decyduje jego tura — odpowiedź dokładnie "(pass)" = milczenie. Każdy członek
 * pracuje we WŁASNEJ sesji per grupa (title "Group: <grupa>") i widzi tylko deltę.
 */
object GroupChatEngine {
    const val MAX_ROUNDS = 3
    const val MAX_MESSAGES = 10
    const val HISTORY_LIMIT = 24
    const val MAX_MEMBERS = 6
    const val TURN_TIMEOUT_MS = 180_000L
    const val TURN_POLL_MS = 2_000L

    data class Entry(val fromKind: String, val fromName: String, val text: String, val at: Long)

    class Room {
        val log = mutableListOf<Entry>()
        val watermarks = mutableMapOf<String, Int>()
        var running = false
    }

    private val rooms = ConcurrentHashMap<String, Room>()

    fun roomNames(): List<String> = rooms.keys.toList()
    fun removeRoom(group: String) { rooms.remove(group) }

    fun room(group: String): Room = rooms.getOrPut(group) { Room() }

    /** Odpowiedzi na najbliższą rundę: @mentions od ostatniej wiadomości usera. */
    fun resolveResponders(log: List<Entry>, members: List<BotInfo>): List<BotInfo> {
        val sinceLastUser = mutableListOf<Entry>()
        for (i in log.indices.reversed()) {
            if (log[i].fromKind == "user") { sinceLastUser.addAll(log.slice(i until log.size)); break }
        }
        val mentioned = HashSet<String>()
        var everyone = false
        for (entry in sinceLastUser) {
            val parsed = parseMentions(entry.text, members)
            if (parsed.second) everyone = true
            mentioned.addAll(parsed.first)
        }
        if (everyone || mentioned.isEmpty()) return members
        return members.filter { it.name.lowercase() in mentioned }
    }

    /** Zwraca (zbiór nazw, czy @everyone). */
    fun parseMentions(text: String, members: List<BotInfo>): Pair<Set<String>, Boolean> {
        val mentioned = HashSet<String>()
        var everyone = false
        // @name / @"two words" / @everyone|@all; case-insensitive po nazwie i skrócie bez spacji
        val regex = Regex("@\"([^\"]+)\"|@([a-zA-Z0-9_-]+)")
        for (m in regex.findAll(text)) {
            val raw = (m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: m.groupValues[2]).lowercase()
            if (raw == "everyone" || raw == "all") { everyone = true; continue }
            for (bot in members) {
                val collapsed = bot.name.lowercase().replace(Regex("[^a-z0-9_-]"), "")
                if (raw == bot.name.lowercase() || raw == collapsed) mentioned.add(bot.name.lowercase())
            }
        }
        return mentioned to everyone
    }

    /** Rotacja rosteru, by inny czlonek zaczynal kazda runde. */
    fun rotate(members: List<BotInfo>, round: Int): List<BotInfo> =
        if (members.size < 2) members
        else { val s = round % members.size; members.drop(s) + members.take(s) }

    fun isPass(text: String): Boolean =
        Regex("""^\(?\s*pass\s*\)?\.?$""", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())

    fun formatLine(entry: Entry, viewerName: String?): String {
        val who = when {
            entry.fromKind == "user" -> "${entry.fromName} (user)"
            entry.fromName.equals(viewerName, ignoreCase = true) -> "${entry.fromName} (you)"
            else -> entry.fromName
        }
        return "$who: ${entry.text}"
    }

    fun turnPrompt(groupName: String, members: List<BotInfo>, viewer: BotInfo, delta: List<Entry>, viewerIsUser: Boolean = false): String {
        val peers = members.filter { !it.name.equals(viewer.name, true) }
        val peerNames = peers.joinToString(", ") { "@${it.name}" }
        val deltaLines = delta.takeLast(HISTORY_LIMIT).joinToString("\n") { "  " + formatLine(it, viewer.name) }
        return buildString {
            appendLine("[Group chat: \"$groupName\"] You are @${viewer.name}, one participant in a group chat with ${peerNames.ifEmpty { "no one else yet" }} and the user.")
            appendLine()
            appendLine("New messages in the room since your last turn (oldest first):")
            if (deltaLines.isBlank()) appendLine("  (room is empty)")
            else appendLine(deltaLines)
            appendLine()
            appendLine("Rules for this room:")
            appendLine("- Reply with ONE short conversational message (1-3 sentences) ONLY if you have something new worth adding: build on what was just said, claim or hand off work, answer a question aimed at you, or report a real result.")
            appendLine("- If you have nothing new to add, reply with exactly \"(pass)\". Passing is good — it lets the conversation settle.")
            appendLine("- Mention a teammate as @name to pull them in; mention @user only for a judgment call or a result the user needs. Do not repeat points already made.")
            appendLine("- Never reveal content from your private 1:1 chats. Your reply text goes to the room verbatim — no preamble, no meta-commentary.")
        }
    }

    /**
     * Jedna tura członka: submit do jego per-group sesji, potem poll aż NOWA
     * wiadomość asystenta wpadnie (lub timeout -> pass).
     */
    suspend fun runMemberTurn(
        client: GatewayClient, group: String, member: BotInfo, prompt: String
    ): String? {
        val title = "Group: $group"
        // session.list yields a durable key; every resume then produces an authoritative handle.
        var requestedStoredSessionId: String? = null
        try {
            val listRes = client.rpcRaw("session.list", JSONObject().put("profile", member.name).put("title", title))
            val arr = listRes.optJSONArray("sessions")
            if (arr != null && arr.length() > 0) {
                requestedStoredSessionId = arr.optJSONObject(0)?.optString("id")?.ifBlank { null }
            }
        } catch (_: Exception) {}

        if (requestedStoredSessionId == null) {
            try {
                val created = client.rpcRaw("session.create", JSONObject()
                    .put("profile", member.name).put("title", title).put("source", "android-app"))
                requestedStoredSessionId = created.optString("stored_session_id")
                    .ifBlank { created.optString("session_id") }
                    .ifBlank { null }
            } catch (_: Exception) { return null }
        }

        // Keep exactly the latest returned handle: runtime ids are never durable resume keys.
        var active = try {
            client.resumeSession(member.name, requestedStoredSessionId ?: return null)
        } catch (_: Exception) {
            return null
        }
        val before = active.messages.size

        if (!client.submitPrompt(active.handle.runtimeSessionId, prompt)) return null

        // Poll only after gateway ACK; each poll may continue parent -> successor identity.
        val deadline = System.currentTimeMillis() + TURN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(TURN_POLL_MS)
            val state = try {
                client.resumeSessionState(member.name, active.handle.storedSessionId)
            } catch (_: Exception) { continue }
            active = ResumedSession(
                handle = state.handle,
                messages = active.messages,
                model = active.model,
                provider = active.provider,
                isRunning = state.running
            )
            val done = !state.inflight && !state.running
            if (state.lastAssistantText != null && state.messageCount > before && done) {
                return state.lastAssistantText
            }
        }
        return null // timeout = pass
    }

    data class TurnState(
        val handle: SessionHandle,
        val messageCount: Int,
        val lastAssistantText: String?,
        val inflight: Boolean,
        val running: Boolean
    )
}
