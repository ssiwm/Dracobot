package eu.draconest.hermesbots.data

import eu.draconest.hermesbots.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BotInfo(
    val name: String,
    val model: String?,
    val skillCount: Int,
    val gatewayRunning: Boolean
)

data class SessionInfo(
    val id: String,
    val title: String,
    val preview: String,
    val messageCount: Int,
    val startedAt: Long,
    val lastActive: Long = 0
)

/** Podsumowanie rozmów profilu dla karty na rosterze. */
data class RosterSummary(
    val chatCount: Int,
    val newestTitle: String,
    val newestPreview: String,
    val newestActiveAt: Long
)

data class RoutineInfo(
    val jobId: String,
    val title: String,
    val schedule: String,
    val active: Boolean,
    val nextRunAt: Long?
)

/** Stan gniazda WS — niezależny od "czy w ogóle zalogowano". */
enum class LinkState { UP, DOWN }

/**
 * Serialize the only model-switch command shape accepted by the Hermes gateway.
 * Kept pure so the Android client and its regression test share one contract.
 */
internal fun buildSessionModelSwitchCommand(provider: String, model: String): String {
    val cleanProvider = provider.trim()
    val cleanModel = model.trim()
    require(cleanProvider.isNotEmpty()) { "Provider is required" }
    require(cleanModel.isNotEmpty()) { "Model is required" }
    return "$cleanModel --provider $cleanProvider --session"
}

/** Convert an RPC exception into text safe to display rather than throw from UI. */
internal fun modelSwitchErrorForUi(error: Throwable): String =
    (error.message ?: "Nie udało się zmienić modelu")
        .removePrefix("RPC config.set: ")

/**
 * Klient Hermes dashboard WS JSON-RPC — port z działającego PoC (poc_gateway.py):
 * auth: token ze SPA HTML (__HERMES_SESSION_TOKEN__), WS /api/ws?token=...
 * metody: profiles.list / session.create{profile} / prompt.submit{session_id,text}
 * stream: method=event, params.type=message.delta|message.complete, params.payload.text
 */
class GatewayClient(private val ok: OkHttpClient = OkHttpClient()) {

    private var ws: WebSocket? = null
    private var baseUrl: String = ""
    private var basicAuth: String? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    private val _events = MutableSharedFlow<JSONObject>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events = _events.asSharedFlow()

    private val _linkState = MutableStateFlow(LinkState.DOWN)
    val linkState = _linkState.asStateFlow()

    // Release (bots.draconest.eu): normalny, publiczny cert CF/Let's Encrypt — pelna weryfikacja.
    // Debug (IP:9443 / localhost): trustAll dla self-signed certu Caddy.
    private val http: OkHttpClient = run {
        val base = ok.newBuilder()
            .addInterceptor { chain ->
                // Własny UA — Cloudflare nie lubi domyślnego "okhttp"
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "HermesBots/0.5")
                        .build()
                )
            }
        if (BuildConfig.DEBUG) {
            val trustAll = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(
                    chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
            val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustAll), java.security.SecureRandom())
            }
            base.sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .build()
        } else {
            base.build()
        }
    }

    /** Pobiera token sesji dashboardu (tryb loopback; opcjonalnie za proxy z Basic Auth). */
    private suspend fun fetchToken(): String {
        val builder = Request.Builder().url("$baseUrl/")
        basicAuth?.let { builder.header("Authorization", it) }
        val body = http.newCall(builder.build()).await().use { resp ->
            if (resp.code == 401) {
                error("Proxy odrzuciło login/hasło (401). Sprawdź użytkownika i hasło.")
            }
            check(resp.isSuccessful) { "HTTP ${resp.code} z dashboardu" }
            resp.body!!.string()
        }
        val re = Regex("""window\.__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""")
        return re.find(body)?.groupValues?.get(1)
            ?: error("Brak tokenu w SPA (gated mode?) — zob. skill hermes-gateway-client")
    }

    /** Łączy WS i zwraca po otwarciu. Zrzuca wyjątek przy błędzie auth/połączenia. */
    suspend fun connect(url: String, username: String = "", password: String = "") {
        close()
        baseUrl = url.trimEnd('/')
        if (username.isNotBlank()) {
            val cred = okhttp3.Credentials.basic(username, password)
            basicAuth = cred
        } else {
            basicAuth = null
        }
        val token = fetchToken()
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/api/ws?token=" + token
        val deferred = CompletableDeferred<Unit>()
        val builder = Request.Builder().url(wsUrl)
        basicAuth?.let { builder.header("Authorization", it) }
        ws = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _linkState.value = LinkState.UP
                deferred.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _linkState.value = LinkState.DOWN
                pending.values.forEach { it.completeExceptionally(t) }
                pending.clear()
                deferred.completeExceptionally(t)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _linkState.value = LinkState.DOWN
                pending.values.forEach { it.cancel() }
                pending.clear()
            }
            override fun onMessage(webSocket: WebSocket, text: String) = route(text)
        })
        deferred.await()
    }

    private fun route(text: String) {
        val frame = try { JSONObject(text) } catch (_: Exception) { return }
        val id = frame.optInt("id", -1)
        if (id > 0 && frame.has("id")) {
            pending.remove(id)?.complete(frame)
        } else if (frame.optString("method") == "event") {
            _events.tryEmit(frame.optJSONObject("params") ?: JSONObject())
        }
    }

    suspend fun rpc(method: String, params: JSONObject): JSONObject {
        val socket = ws ?: error("WS niepołączony")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("method", method).put("params", params)
        if (!socket.send(msg.toString())) error("Nie udało się wysłać ramki")
        val resp = deferred.await()
        resp.optJSONObject("error")?.let {
            error("RPC $method: ${it.optString("message")} (${it.optString("code")})")
        }
        return resp.optJSONObject("result") ?: JSONObject()
    }

    suspend fun listProfiles(): List<BotInfo> {
        val res = rpc("profiles.list", JSONObject())
        val arr = res.optJSONArray("profiles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                BotInfo(
                    name = o.optString("name"),
                    model = o.optString("model").ifBlank { null },
                    skillCount = o.optInt("skill_count", 0),
                    gatewayRunning = o.optBoolean("gateway_running", false)
                )
            }
        }.sortedBy { it.name != "default" } // default na górze rostera
    }

    suspend fun createSession(profile: String): String {
        val res = rpc("session.create", JSONObject()
            .put("profile", profile)
            .put("title", "Hermes Bots (Android)")
            .put("source", "android-app"))
        return res.optString("session_id").ifBlank { res.optString("sid") }.also {
            require(it.isNotBlank()) { "session.create bez session_id: $res" }
        }
    }

    /** Lista rozmów danego bota (profilu) do wyboru / wznowienia. */
    suspend fun listSessions(profile: String): List<SessionInfo> {
        val res = rpc("session.list", JSONObject().put("profile", profile).put("limit", 30))
        val arr = res.optJSONArray("sessions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                SessionInfo(
                    id = o.optString("id"),
                    title = o.optString("title").ifBlank { "(bez tytułu)" },
                    preview = o.optString("preview"),
                    messageCount = o.optInt("message_count", 0),
                    startedAt = o.optLong("started_at", 0),
                    lastActive = o.optLong("last_active", 0)
                )
            }
        }.filter { it.messageCount > 0 || it.preview.isNotBlank() }
            // szum: sesje cron/scheduler nie sa rozmowami uzytkownika
            .filter { !it.id.startsWith("cron_") && !it.preview.startsWith("[IMPORTANT") }
    }

    /** Podsumowanie aktywnosci profilu dla rosteru: liczba rozmów + najnowsza. */
    suspend fun rosterSummary(profile: String): RosterSummary? =
        try {
            val sessions = listSessions(profile)
            if (sessions.isEmpty()) null else {
                val newest = sessions.maxBy { maxOf(it.lastActive, it.startedAt) }
                RosterSummary(
                    chatCount = sessions.size,
                    newestTitle = newest.title,
                    newestPreview = newest.preview,
                    newestActiveAt = maxOf(newest.lastActive, newest.startedAt)
                )
            }
        } catch (_: Exception) {
            null
        }

    /**
     * Wznawia istniejącą rozmowę i zwraca (nowe session_id runtime, historia).
     * Historia: pary (odUzytkownika, tekst) — tylko role user/assistant.
     */
    suspend fun resumeSession(profile: String, sessionId: String): Pair<String, List<Pair<Boolean, String>>> {
        val res = rpc("session.resume", JSONObject()
            .put("session_id", sessionId)
            .put("profile", profile)
            .put("cols", 80))
        val sid = res.optString("session_id").ifBlank { res.optString("resumed", sessionId) }
        val out = ArrayList<Pair<Boolean, String>>()
        val arr = res.optJSONArray("messages")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val role = m.optString("role")
                if (role != "user" && role != "assistant") continue // tool/system pomijamy
                val text = m.optString("text")
                if (text.isNotBlank()) out.add((role == "user") to text)
            }
        }
        // model/provider sesji z info (do wyswietlenia i przelaczania w UI)
        val info = res.optJSONObject("info")
        currentModel.value = info?.optString("model").orEmpty()
        currentProvider.value = info?.optString("provider").orEmpty()
        return sid to out
    }

    /** Aktualny model/provider aktywnej sesji (uzupełniane przy resume). */
    val currentModel = kotlinx.coroutines.flow.MutableStateFlow("")
    val currentProvider = kotlinx.coroutines.flow.MutableStateFlow("")

    /** Dostępni providerzy i modele dla wskazanej sesji (model.options). */
    suspend fun modelOptions(sessionId: String? = null): List<Pair<String, List<String>>> {
        val params = JSONObject()
        if (!sessionId.isNullOrBlank()) params.put("session_id", sessionId)
        val res = rpc("model.options", params)
        val providers = mutableListOf<Pair<String, List<String>>>()
        val arr = res.optJSONArray("providers") ?: return providers
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val models = mutableListOf<String>()
            val ma = p.optJSONArray("models")
            if (ma != null) for (j in 0 until ma.length()) models.add(ma.optString(j))
            providers.add(p.optString("slug") to models)
        }
        return providers
    }

    /**
     * Zmienia model wyłącznie w bieżącej sesji. Błędy config.set są wynikiem
     * dla UI (Toast), a nie nieobsłużonym wyjątkiem coroutine na wątku głównym.
     */
    suspend fun setSessionModel(sessionId: String, provider: String, model: String): String? = try {
        val command = buildSessionModelSwitchCommand(provider, model)
        val res = rpc("config.set", JSONObject()
            .put("session_id", sessionId)
            .put("key", "model")
            .put("value", command))
        currentModel.value = res.optString("value").ifBlank { model.trim() }
        currentProvider.value = provider.trim()
        null
    } catch (e: Exception) {
        modelSwitchErrorForUi(e)
    }

    fun submitPrompt(sessionId: String, text: String): Boolean {
        return rpcAsync("prompt.submit", JSONObject()
            .put("session_id", sessionId).put("text", text))
    }

    private fun rpcAsync(method: String, params: JSONObject): Boolean {
        val socket = ws ?: return false
        if (_linkState.value != LinkState.UP) return false // martwe gniazdo — nie udawaj wysylki
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        val sent = socket.send(JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("method", method).put("params", params).toString())
        if (!sent) {
            pending.remove(id)
            _linkState.value = LinkState.DOWN
        }
        return sent
    }

    fun close() {
        ws?.close(1000, "bye"); ws = null
        pending.clear()
    }

    /** Routines bota = cron jobs otagowane [bot:<nazwa>] (jak desktop Bot Mode). */
    suspend fun listRoutines(profile: String): List<RoutineInfo> {
        val res = rpc("cron.manage", JSONObject()
            .put("action", "list")
            .put("include_disabled", true)
            .put("profile", profile))
        val jobs = res.optJSONArray("jobs") ?: return emptyList()
        val tagRe = Regex("""^\[bot:([a-z0-9][a-z0-9_-]*)]\s*""", RegexOption.IGNORE_CASE)
        return (0 until jobs.length()).mapNotNull { i ->
            jobs.optJSONObject(i)?.let { o ->
                val name = o.optString("name")
                val match = tagRe.find(name)
                val owner = match?.groupValues?.get(1)?.lowercase()
                    ?: return@mapNotNull null // bez taga = nie jest routine bota
                if (owner != profile.lowercase()) return@mapNotNull null
                RoutineInfo(
                    jobId = o.optString("job_id"),
                    title = name.replace(tagRe, "").ifBlank { "(bez tytułu)" },
                    schedule = o.optString("schedule"),
                    active = o.optBoolean("enabled", true) &&
                        !o.optString("state", "").equals("paused", ignoreCase = true),
                    nextRunAt = if (o.has("next_run_at")) o.optLong("next_run_at") else null
                )
            }
        }
    }

    /** Pause/resume routine. */
    suspend fun setRoutineActive(profile: String, jobId: String, active: Boolean) {
        rpc("cron.manage", JSONObject()
            .put("action", if (active) "resume" else "pause")
            .put("name", jobId)
            .put("profile", profile))
    }

    /** Resume + stan tury (dla GroupChatEngine): liczba wiadomosci, ostatni assistant, inflight. */
    suspend fun resumeSessionState(profile: String, sessionId: String): GroupChatEngine.TurnState {
        val res = rpc("session.resume", JSONObject()
            .put("session_id", sessionId).put("profile", profile).put("cols", 80))
        val msgs = res.optJSONArray("messages")
        var count = res.optInt("message_count", 0)
        var lastAssistant: String? = null
        if (msgs != null) {
            count = maxOf(count, msgs.length())
            for (i in msgs.length() - 1 downTo 0) {
                val m = msgs.optJSONObject(i) ?: continue
                if (m.optString("role") == "assistant") {
                    val t = if (m.has("text")) m.optString("text") else m.optString("content")
                    if (t.isNotBlank()) { lastAssistant = t; break }
                }
            }
        }
        val inflight = res.optJSONObject("inflight")?.let { it.length() > 0 } ?: false
        return GroupChatEngine.TurnState(count, lastAssistant, inflight,
            res.optBoolean("running", false))
    }

    /** Niskopoziomowe RPC dla GroupChatEngine. */
    suspend fun rpcRaw(method: String, params: JSONObject): JSONObject = rpc(method, params)

    // ---- Załączniki ----

    /** Wynik załączenia pliku (z gatewaya). */
    data class AttachResult(val ok: Boolean, val message: String)

    /**
     * Zalacz obrazek do sesji (image.attach_bytes, base64).
     * Obrazek trafia do kolejki sesji i pójdzie z następnym promptem.
     */
    suspend fun attachImage(sessionId: String, bytes: ByteArray, filename: String): AttachResult {
        return try {
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val res = rpc("image.attach_bytes", JSONObject()
                .put("session_id", sessionId)
                .put("content_base64", b64)
                .put("filename", filename))
            if (res.optBoolean("attached")) {
                AttachResult(true, res.optString("text"))
            } else {
                AttachResult(false, res.optString("message").ifBlank { "Nie udało się załączyć" })
            }
        } catch (e: Exception) {
            AttachResult(false, e.message ?: "Błąd załączania")
        }
    }

    /**
     * Zalacz PDF (pdf.attach, base64) — serwer renderuje strony na obrazy wizyjne.
     */
    suspend fun attachPdf(sessionId: String, bytes: ByteArray, filename: String): AttachResult {
        return try {
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val res = rpc("pdf.attach", JSONObject()
                .put("session_id", sessionId)
                .put("content_base64", b64)
                .put("filename", filename))
            if (res.optBoolean("attached") || res.has("pages")) {
                AttachResult(true, res.optString("text").ifBlank { "PDF załączony (${res.optInt("pages")} str.)" })
            } else {
                AttachResult(false, res.optString("message").ifBlank { "Nie udało się załączyć PDF" })
            }
        } catch (e: Exception) {
            AttachResult(false, e.message ?: "Błąd załączania PDF")
        }
    }

    /**
     * Zalacz plik inny niż obraz/PDF (file.attach z data_url) — plik trafia
     * do workspace'u sesji, agent moze go czytac narzedziami.
     */
    suspend fun attachFile(sessionId: String, bytes: ByteArray, filename: String, mime: String): AttachResult {
        return try {
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val res = rpc("file.attach", JSONObject()
                .put("session_id", sessionId)
                .put("name", filename)
                .put("data_url", "data:$mime;base64,$b64"))
            if (res.has("error")) AttachResult(false, res.optString("message").ifBlank { "Nie udało się załączyć pliku" })
            else AttachResult(true, res.optString("text").ifBlank { "Plik $filename załączony" })
        } catch (e: Exception) {
            AttachResult(false, e.message ?: "Błąd załączania pliku")
        }
    }

    /** Utwórz bota przez REST POST /api/profiles (klon z mirror credentials). */
    suspend fun createBot(name: String) {
        val token = fetchToken()
        val json = JSONObject().put("name", name).put("mirror_credentials", true)
        val req = okhttp3.Request.Builder()
            .url("$baseUrl/api/profiles")
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), json.toString()))
            .apply { basicAuth?.let { header("Authorization", it) } }
            .header("X-Hermes-Session-Token", token)
            .header("User-Agent", "HermesBots/0.10")
            .build()
        http.newCall(req).await().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
        }
    }

    /** Usuń bota przez REST DELETE /api/profiles/{name}. */
    suspend fun deleteBot(name: String) {
        val token = fetchToken()
        val req = okhttp3.Request.Builder()
            .url("$baseUrl/api/profiles/$name")
            .delete()
            .apply { basicAuth?.let { header("Authorization", it) } }
            .header("X-Hermes-Session-Token", token)
            .header("User-Agent", "HermesBots/0.10")
            .build()
        http.newCall(req).await().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response)
            }
        })
        cont.invokeOnCancellation { cancel() }
    }
}
