package eu.draconest.hermesbots.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false
) {
    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(1)
        fun nextId(): Long = counter.getAndIncrement()
    }
}

class AppViewModel : ViewModel() {
    private val client = GatewayClient()
    lateinit var store: AppStore

    val connected = MutableStateFlow(false)
    val connecting = MutableStateFlow(false)
    val connectionError = MutableStateFlow<String?>(null)
    val bots = MutableStateFlow<List<BotInfo>>(emptyList())
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val activeBot = MutableStateFlow<BotInfo?>(null)
    val thinking = MutableStateFlow(false)
    /** Akumulowany tekst rozumowania (reasoning.delta) — podglad "myslenia". */
    val thinkingText = MutableStateFlow("")
    /** Status procesu (thinking.delta) — pojedyncza linia, gdy brak reasoning. */
    val statusText = MutableStateFlow("")
    val thinkingHasContent = MutableStateFlow(false)
    /** Czy panel myslenia rozwiniety (user moze zwinac/rozwinac). */
    val thinkingOpen = MutableStateFlow(true)
    private var thinkingHistory = MutableStateFlow("")

    fun toggleThinking() { thinkingOpen.value = !thinkingOpen.value }

    // ---- Model AI sesji ----
    val currentModel get() = client.currentModel
    val currentProvider get() = client.currentProvider

    /** Zmien model biezacej rozmowy; zwraca komunikat bledu lub null. */
    suspend fun switchModel(value: String): String? {
        val sid = sessionId ?: return "Brak aktywnej rozmowy"
        return client.setSessionModel(sid, value)
    }

    /** Lista provider/modeli dla pickera. */
    suspend fun loadModelOptions(): List<Pair<String, List<String>>> = client.modelOptions()

    // ---- Załączniki ----

    data class AttachedItem(val name: String, val message: String)
    val attachments = MutableStateFlow<List<AttachedItem>>(emptyList())
    private val _attachError = MutableStateFlow<String?>(null)
    val attachError = _attachError

    /**
     * Zalacz plik: czyta bytes z ContentResolver, wybiera RPC wg MIME.
     * Wynik dolacza jako wiadomosc w czacie.
     */
    fun attachFromUri(context: android.content.Context, uri: android.net.Uri) {
        val sid = sessionId
        if (sid == null) { _attachError.value = "Najpierw otwórz rozmowę"; return }
        viewModelScope.launch {
            try {
                val resolver = context.contentResolver
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) { _attachError.value = "Plik pusty lub nieczytelny"; return@launch }
                if (bytes.size > 45 * 1024 * 1024) { _attachError.value = "Plik za duży (limit ~45 MB)"; return@launch }
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "plik"
                val result = when {
                    mime.startsWith("image/") -> client.attachImage(sid, bytes, name)
                    mime == "application/pdf" -> client.attachPdf(sid, bytes, name)
                    else -> client.attachFile(sid, bytes, name, mime)
                }
                if (result.ok) {
                    attachments.value = attachments.value + AttachedItem(name, result.message)
                    messages.value += ChatMessage(ChatMessage.nextId(), fromUser = false, text = "📎 ${result.message}")
                    _attachError.value = null
                } else {
                    _attachError.value = result.message
                }
            } catch (e: Exception) {
                _attachError.value = e.message ?: "Błąd odczytu pliku"
            }
        }
    }

    fun clearAttachError() { _attachError.value = null }
    val sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    /** "offline" = zalogowani, ale WS padl (apka w tle itp.) */
    val offline = MutableStateFlow(false)

    private var sessionId: String? = null
    private var eventsJob: Job? = null
    private var linkWatchJob: Job? = null
    private var reconnectJob: Job? = null
    private val outbox = ArrayDeque<String>() // niewyslane wiadomosci czekajace na link

    /** Obserwuje stan gniazda WS; przy zerwaniu uruchamia cichy reconnect. */
    fun observeLink() {
        if (linkWatchJob?.isActive == true) return
        linkWatchJob = viewModelScope.launch {
            client.linkState.collect { state ->
                if (state == LinkState.DOWN && connected.value && !offline.value) {
                    offline.value = true
                    quietReconnectLoop()
                }
            }
        }
    }

    /** Cichy reconnect: nie rusza ekranow, po powrocie odswieza biezaca rozmowe. */
    private fun quietReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            while (connected.value && client.linkState.value == LinkState.DOWN) {
                delay(5_000)
                try {
                    client.connect(store.url, store.username, store.password)
                    subscribeEvents()
                    bots.value = client.listProfiles()
                    connected.value = true
                    connectionError.value = null
                    offline.value = false
                    refreshCurrentChatAfterReconnect()
                    flushOutbox()
                    break
                } catch (_: Exception) {
                    // sprobuj znowu za 5 s
                }
            }
        }
    }

    /** Po reconnect/resume: przeladuj historie biezacej rozmowy — odzyska to, co przepadlo,
     * i zdjmie zawieszony status "mysli", jesli tura faktycznie sie skonczyla. */
    private suspend fun refreshCurrentChatAfterReconnect() {
        val bot = activeBot.value ?: return
        val stored = store.lastSessionId
        val target = sessionId ?: stored.takeIf { it.isNotBlank() } ?: return
        try {
            val (_, history) = kotlinx.coroutines.withTimeout(15_000) {
                client.resumeSession(bot.name, target)
            }
            val restored = history.map { (fromUser, text) ->
                ChatMessage(ChatMessage.nextId(), fromUser, text)
            }
            // nie nadpisuj, jesli user cos wpisal lokalnie, czego nie ma w historii
            val localOnly = messages.value.any { msg ->
                msg.fromUser && restored.none { r -> r.fromUser && r.text == msg.text }
            }
            if (!localOnly && restored.isNotEmpty()) {
                messages.value = restored
            }
            thinking.value = false // historia pokazuje stan serwera — "mysli" juz nieaktualne
        } catch (_: Exception) {
            // sesja mogla wygasnac — zostaw co jest, user otworzy z listy
        }
    }

    private fun flushOutbox() {
        val sid = sessionId ?: return
        while (outbox.isNotEmpty()) {
            client.submitPrompt(sid, outbox.removeFirst())
        }
    }

    /** Auto-connect przy starcie, jesli mamy zapisane logowanie. */
    fun autoConnect() {
        if (connected.value || connecting.value) return
        store ?: return
        if (store.hasCredentials) connect(store.url, store.username, store.password)
    }

    /** Wywolac przy zmianie sieci / powrocie apki na wierzch.
     * Zawsze robi health-check i odswieza rozmowe — leczy "zawieszone mysli" po tle. */
    fun onNetworkMaybeRestored() {
        if (!::store.isInitialized || !store.hasCredentials) return
        viewModelScope.launch {
            if (!connected.value) {
                connect(store.url, store.username, store.password, rememberStateAfter = true)
                return@launch
            }
            // zalogowani: sprawdz czy gniazdo naprawde zyje (Android czesto je zabija w tle bez onFailure)
            val healthy = try {
                kotlinx.coroutines.withTimeout(8_000) { client.listProfiles() }
                true
            } catch (_: Exception) {
                false
            }
            if (!healthy) {
                // wymuszone przełaczenie linku na DOWN -> cichy reconnect
                offline.value = true
                quietReconnectLoop()
            } else {
                // gniazdo zyje — ale odpowiedzi mogly wpasc podczas tla: odswiez i odblokuj "mysli"
                refreshCurrentChatAfterReconnect()
                flushOutbox()
            }
        }
    }

    fun connect(url: String, username: String, password: String) =
        connect(url, username, password, saveCredentials = true)

    private fun connect(
        url: String,
        username: String,
        password: String,
        saveCredentials: Boolean = false,
        rememberStateAfter: Boolean = false
    ) = viewModelScope.launch {
        connecting.value = true
        connectionError.value = null
        try {
            client.connect(url, username.trim(), password)
            subscribeEvents()
            bots.value = client.listProfiles()
            connected.value = true
            loadRosterSummaries()
            if (saveCredentials && ::store.isInitialized) {
                store.url = url.trimEnd('/')
                store.username = username.trim()
                store.password = password
                store.saveProxyForBridge(username.trim(), password)
            }
            if (rememberStateAfter && ::store.isInitialized && store.lastBotName.isNotBlank()) {
                // przywroc ostatni kontekst rozmowy po reconnect
                restoreLastBot()
            } else if (::store.isInitialized && store.lastBotName.isNotBlank()) {
                restoreLastBot()
            }
        } catch (e: Exception) {
            connectionError.value = e.message ?: "Nieznany błąd połączenia"
            scheduleReconnect()
        } finally {
            connecting.value = false
        }
    }

    /** Po utacie WS: probuj cicho co 10 s (max bez konca, ale tylko gdy zapisane dane). */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        if (::store.isInitialized && !store.hasCredentials) return
        reconnectJob = viewModelScope.launch {
            while (!connected.value) {
                delay(10_000)
                try {
                    client.connect(store.url, store.username, store.password)
                    subscribeEvents()
                    bots.value = client.listProfiles()
                    connected.value = true
                    connectionError.value = null
                    restoreLastBot()
                    break
                } catch (_: Exception) {
                    // sprobuj znowu za 10s
                }
            }
        }
    }

    private suspend fun restoreLastBot() {
        val name = store.lastBotName
        if (name.isBlank()) return
        val bot = bots.value.firstOrNull { it.name == name } ?: return
        activeBot.value = bot
        messages.value = emptyList()
        sessions.value = emptyList()
        sessionId = null
        val lastSession = store.lastSessionId
        if (lastSession.isNotBlank()) {
            try {
                val (sid, history) = client.resumeSession(bot.name, lastSession)
                sessionId = sid
                messages.value = history.map { (fromUser, text) ->
                    ChatMessage(ChatMessage.nextId(), fromUser, text)
                }
                return
            } catch (_: Exception) {
                // sesja wygasla/zarchiwizowana -> pokaz wybor
            }
        }
        val list = client.listSessions(bot.name).sortedByDescending { it.startedAt }
        if (list.isEmpty()) sessionId = client.createSession(bot.name) else sessions.value = list
    }

    private fun subscribeEvents() {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            client.events.collect { p ->
                when (p.optString("type")) {
                    "message.delta" -> {
                        appendDelta(p.optJSONObject("payload")?.optString("text") ?: "")
                        // odpowiedz ruszyła — chowamy podglad myslenia
                        if (thinkingText.value.isNotBlank()) {
                            thinkingHistory.value = thinkingText.value
                            thinkingOpen.value = false
                        }
                    }
                    "message.complete" -> {
                        finishMessage(p.optJSONObject("payload")?.optString("text"))
                        thinkingText.value = ""
                    }
                    "reasoning.delta" -> {
                        // przyrost tekstu rozumowania (jak w CLI: _reasoning_buf += text)
                        val t = p.optJSONObject("payload")?.optString("text") ?: return@collect
                        if (t.isNotEmpty()) {
                            thinkingText.value = (thinkingText.value + t).takeLast(8000)
                            thinking.value = true
                            thinkingHasContent.value = true
                        }
                    }
                    "thinking.delta" -> {
                        // status procesu ("Analizuję plik...", "Czekam na API...") — pelny tekst, nie kumulowany
                        val t = p.optJSONObject("payload")?.optString("text") ?: return@collect
                        if (t.isNotEmpty() && !thinkingHasContent.value) {
                            statusText.value = t
                        }
                        thinking.value = true
                    }
                }
            }
        }
    }

    private fun appendDelta(delta: String) {
        if (delta.isEmpty()) return
        thinking.value = false
        val list = messages.value.toMutableList()
        val last = list.lastOrNull()
        if (last != null && !last.fromUser && last.streaming) {
            list[list.lastIndex] = last.copy(text = last.text + delta)
        } else {
            list.add(ChatMessage(ChatMessage.nextId(), fromUser = false, text = delta, streaming = true))
        }
        messages.value = list
    }

    private fun finishMessage(full: String?) {
        thinking.value = false
        val list = messages.value.toMutableList()
        val last = list.lastOrNull()
        when {
            list.isEmpty() && !full.isNullOrEmpty() ->
                list.add(ChatMessage(ChatMessage.nextId(), fromUser = false, text = full))
            full != null && full.isNotEmpty() && last != null && !last.fromUser ->
                list[list.lastIndex] = last.copy(text = full, streaming = false)
            last != null && !last.fromUser -> list[list.lastIndex] =
                last.copy(streaming = false)
        }
        messages.value = list
    }

    fun openChat(bot: BotInfo) {
        activeBot.value = bot
        messages.value = emptyList()
        sessions.value = emptyList()
        sessionId = null
        store.lastBotName = bot.name
        viewModelScope.launch {
            try {
                val list = client.listSessions(bot.name)
                    .sortedByDescending { it.startedAt }
                if (list.isEmpty()) {
                    sessionId = client.createSession(bot.name)
                } else {
                    sessions.value = list
                }
            } catch (e: Exception) {
                messages.value = listOf(ChatMessage(ChatMessage.nextId(), false, "⚠️ ${e.message}"))
            }
        }
    }

    fun startNewSession() {
        val bot = activeBot.value ?: return
        sessions.value = emptyList()
        messages.value = emptyList()
        store.lastSessionId = ""
        viewModelScope.launch {
            try {
                sessionId = client.createSession(bot.name)
            } catch (e: Exception) {
                messages.value = listOf(ChatMessage(ChatMessage.nextId(), false, "⚠️ ${e.message}"))
            }
        }
    }

    fun resumeChat(info: SessionInfo) {
        val bot = activeBot.value ?: return
        sessions.value = emptyList()
        thinking.value = true
        viewModelScope.launch {
            try {
                val (sid, history) = client.resumeSession(bot.name, info.id)
                sessionId = sid
                store.lastSessionId = info.id
                messages.value = history.map { (fromUser, text) ->
                    ChatMessage(ChatMessage.nextId(), fromUser, text)
                }
            } catch (e: Exception) {
                messages.value = listOf(ChatMessage(ChatMessage.nextId(), false, "⚠️ ${e.message}"))
            } finally {
                thinking.value = false
            }
        }
    }

    fun send(text: String) {
        val sid = sessionId
        if (sid == null) return
        messages.value += ChatMessage(ChatMessage.nextId(), fromUser = true, text = text)
        // nowa tura: czysc podglad myslenia
        thinkingText.value = ""
        statusText.value = ""
        thinkingHasContent.value = false
        thinkingOpen.value = true
        val sent = client.submitPrompt(sid, text)
        if (sent) {
            thinking.value = true
        } else {
            outbox.addLast(text) // wyśle się po reconnect; historia i tak wróci z serwera
            offline.value = true
            quietReconnectLoop()
        }
    }

    fun closeChat() {
        activeBot.value = null
        messages.value = emptyList()
        sessions.value = emptyList()
        sessionId = null
        thinking.value = false
        routines.value = emptyList()
        if (::store.isInitialized) {
            store.lastBotName = ""
            store.lastSessionId = ""
        }
    }

    // ---- Routines ----

    val routines = MutableStateFlow<List<RoutineInfo>>(emptyList())
    val creatingBot = MutableStateFlow(false)
    private var showRoutines = false

    /** Czy pokazujemy panel Routines zamiast rozmow (stan UI). */
    private val _viewRoutines = MutableStateFlow(false)
    val viewRoutines = _viewRoutines.asStateFlow()

    fun openRoutines() {
        val bot = activeBot.value ?: return
        _viewRoutines.value = true
        viewModelScope.launch {
            try {
                routines.value = client.listRoutines(bot.name)
                    .sortedByDescending { it.active }
            } catch (e: Exception) {
                routines.value = emptyList()
            }
        }
    }

    fun closeRoutines() {
        _viewRoutines.value = false
    }

    fun toggleRoutine(routine: RoutineInfo, enable: Boolean) {
        val bot = activeBot.value ?: return
        // optymistycznie
        routines.value = routines.value.map {
            if (it.jobId == routine.jobId) it.copy(active = enable) else it
        }
        viewModelScope.launch {
            try {
                client.setRoutineActive(bot.name, routine.jobId, enable)
            } catch (_: Exception) {
                // rollback
                routines.value = routines.value.map {
                    if (it.jobId == routine.jobId) it.copy(active = !enable) else it
                }
            }
        }
    }

    // ---- Grupy botów ----

    val groups = MutableStateFlow<List<String>>(emptyList())
    val activeGroup = MutableStateFlow<String?>(null)
    val groupLog = MutableStateFlow<List<GroupChatEngine.Entry>>(emptyList())
    val groupRunning = MutableStateFlow(false)

    private var groupEpoch = 0

    /** Tworzy grupe z wybranych botow (2-6). */
    fun createGroup(name: String, members: List<BotInfo>) {
        val safeName = name.trim().ifBlank { "Grupa" }
        GroupChatEngine.room(safeName) // inicjalizuj
        refreshGroupsList()
        openGroup(safeName)
        // preseed czlonkow w metadanych pokoju (log pusty)
    }

    fun refreshGroupsList() {
        groups.value = GroupChatEngine.roomNames().sorted()
    }

    /** Pull-to-refresh rosteru: odswiez profile (i liste grup). */
    fun refreshRoster() {
        if (!connected.value) return
        viewModelScope.launch {
            try {
                bots.value = client.listProfiles()
                refreshGroupsList()
                loadRosterSummaries()
            } catch (_: Exception) { /* cicho — pull-refresh nie krzyczy */ }
        }
    }

    /** Podsumowania rozmów per bot (badge liczby + ostatnia aktywność na rosterze). */
    val rosterSummaries = MutableStateFlow<Map<String, RosterSummary>>(emptyMap())

    private suspend fun loadRosterSummaries() {
        val map = mutableMapOf<String, RosterSummary>()
        for (bot in bots.value) {
            client.rosterSummary(bot.name)?.let { map[bot.name] = it }
        }
        rosterSummaries.value = map
    }

    // ---- Tworzenie / usuwanie botów (REST /api/profiles) ----

    /** Utwórz bota: klon umiejętności z profilu default + mirror credentials
     *  (bot dostaje dostep do modelu od razu). Nazwa: [a-z0-9][a-z0-9_-]{0,63}. */
    fun createBot(rawName: String) {
        val name = rawName.trim().lowercase()
        if (!Regex("^[a-z0-9][a-z0-9_-]{0,63}$").matches(name)) {
            connectionError.value = "Nazwa: małe litery/cyfry/-/_ (max 64), zaczyna się literą lub cyfrą"
            return
        }
        creatingBot.value = true
        viewModelScope.launch {
            try {
                client.createBot(name)
                bots.value = client.listProfiles()
                connectionError.value = null
            } catch (e: Exception) {
                connectionError.value = "Nie udało się utworzyć bota: ${e.message}"
            } finally {
                creatingBot.value = false
            }
        }
    }

    /** Usuń bota (po potwierdzeniu w UI). Default jest chroniony po stronie serwera. */
    fun deleteBot(name: String) {
        viewModelScope.launch {
            try {
                client.deleteBot(name)
                bots.value = client.listProfiles()
            } catch (e: Exception) {
                connectionError.value = "Nie udało się usunąć bota: ${e.message}"
            }
        }
    }

    fun deleteGroup(name: String) {
        // lokalnie: usuwamy pokoj (sesje botow zostaja na serwerze — historia trwa)
        GroupChatEngine.removeRoom(name)
        if (activeGroup.value == name) closeGroup()
        refreshGroupsList()
    }

    fun openGroup(name: String) {
        activeGroup.value = name
        _viewRoutines.value = false
        sessions.value = emptyList()
        groupLog.value = GroupChatEngine.room(name).log.toList()
    }

    fun closeGroup() {
        activeGroup.value = null
        groupLog.value = emptyList()
    }

    /** Wyslij wiadomosc usera do pokoju i uruchom seryjne rundy botow. */
    fun sendToGroup(text: String) {
        val group = activeGroup.value ?: return
        val members = bots.value.filter { it.name != "default" }.take(GroupChatEngine.MAX_MEMBERS)
        if (members.isEmpty()) return
        val room = GroupChatEngine.room(group)
        room.log.add(GroupChatEngine.Entry("user", "Ty", text.trim(), System.currentTimeMillis() / 1000))
        groupLog.value = room.log.toList()
        groupEpoch += 1

        if (!room.running) {
            room.running = true
            groupRunning.value = true
            viewModelScope.launch { runGroupRounds(group, members, groupEpoch) }
        }
    }

    private suspend fun runGroupRounds(group: String, members: List<BotInfo>, myEpoch: Int) {
        val room = GroupChatEngine.room(group)
        var posted = 0
        try {
            for (round in 0 until GroupChatEngine.MAX_ROUNDS) {
                val responders = GroupChatEngine.rotate(
                    GroupChatEngine.resolveResponders(room.log, members), round)
                var spoke = 0
                for (member in responders) {
                    if (groupEpoch != myEpoch || posted >= GroupChatEngine.MAX_MESSAGES) return
                    val seen = room.watermarks[member.name] ?: 0
                    val delta = room.log.drop(seen)
                    if (delta.isEmpty()) continue

                    val prompt = GroupChatEngine.turnPrompt(group, members, member, delta)
                    val reply = try {
                        kotlinx.coroutines.withTimeout(GroupChatEngine.TURN_TIMEOUT_MS + 5_000) {
                            GroupChatEngine.runMemberTurn(client, group, member, prompt)
                        }
                    } catch (_: Exception) { null }

                    room.watermarks[member.name] = room.log.size
                    if (!reply.isNullOrBlank() && !GroupChatEngine.isPass(reply)) {
                        room.log.add(GroupChatEngine.Entry("member", member.name, reply.trim(),
                            System.currentTimeMillis() / 1000))
                        room.watermarks[member.name] = room.log.size
                        groupLog.value = room.log.toList()
                        posted += 1; spoke += 1
                    }
                }
                if (spoke == 0) return // wszyscy pass — rozmowa osiadla
            }
        } finally {
            if (groupEpoch == myEpoch) {
                room.running = false
                groupRunning.value = false
            }
        }
    }
}
