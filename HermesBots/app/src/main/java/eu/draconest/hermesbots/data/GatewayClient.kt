package eu.draconest.hermesbots.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
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

/** One provider row from model.options; aliases preserve canonical custom-provider IDs. */
data class ModelProviderOption(
    val slug: String,
    val models: List<String>,
    val aliases: List<String> = emptyList()
)

/** Session-scoped catalog plus the gateway's authoritative current identity. */
data class ModelOptionsPayload(
    val providers: List<ModelProviderOption>,
    val model: String,
    val provider: String
)

/** A runtime WS session id paired with the durable key accepted by session.resume. */
data class SessionHandle(
    val runtimeSessionId: String,
    val storedSessionId: String
)

/** session.create returns both identities; old gateways may use one id for both. */
internal fun sessionHandleForCreate(runtimeSessionId: String, storedSessionId: String): SessionHandle {
    val runtime = runtimeSessionId.trim()
    require(runtime.isNotEmpty()) { "session.create without runtime session_id" }
    return SessionHandle(
        runtimeSessionId = runtime,
        storedSessionId = storedSessionId.trim().ifBlank { runtime }
    )
}

/** session.resume receives a persistent key but always returns a fresh runtime session id. */
internal fun sessionHandleForResume(
    requestedStoredSessionId: String,
    returnedRuntimeSessionId: String,
    returnedStoredSessionId: String
): SessionHandle {
    val requested = requestedStoredSessionId.trim()
    require(requested.isNotEmpty()) { "session.resume without stored session key" }
    val runtime = returnedRuntimeSessionId.trim()
    require(runtime.isNotEmpty()) { "session.resume without runtime session_id" }
    return SessionHandle(
        runtimeSessionId = runtime,
        storedSessionId = returnedStoredSessionId.trim().ifBlank { requested }
    )
}

/** A new session handle plus the model identity provided by session.create. */
data class CreatedSession(
    val handle: SessionHandle,
    val model: String,
    val provider: String
)

/** A resumed session handle plus its history and authoritative identity. */
data class ResumedSession(
    val handle: SessionHandle,
    val messages: List<Pair<Boolean, String>>,
    val model: String,
    val provider: String,
    val isRunning: Boolean
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

/** The explicit state returned by a session-scoped model-switch request. */
sealed interface ModelSwitchResult {
    data object Applied : ModelSwitchResult
    data object Deferred : ModelSwitchResult
    data object TimedOut : ModelSwitchResult
    data class ConfirmationRequired(val message: String) : ModelSwitchResult
    data class Failure(val message: String) : ModelSwitchResult
}

/** The acknowledgement outcome for a prompt frame. Only Accepted proves gateway ownership. */
internal sealed interface PromptSubmissionResult {
    data class Accepted(val status: String) : PromptSubmissionResult
    data class Rejected(val code: Int?, val message: String) : PromptSubmissionResult
    data object NotSent : PromptSubmissionResult
    data object Indeterminate : PromptSubmissionResult
}

private const val PROMPT_ACK_TIMEOUT_MS = 12_000L
private const val MODEL_SWITCH_TIMEOUT_MS = 12_000L

/**
 * A guarded model selection is not applied until the user confirms it in a
 * second request with confirm_expensive_model=true.
 */
internal fun modelSwitchOutcome(
    confirmationRequired: Boolean,
    confirmationMessage: String,
    warning: String,
    deferred: Boolean = false
): ModelSwitchResult {
    if (confirmationRequired) {
        val message = confirmationMessage
            .ifBlank { warning }
            .ifBlank { "Wybrany model wymaga potwierdzenia." }
        return ModelSwitchResult.ConfirmationRequired(message)
    }
    return if (deferred) ModelSwitchResult.Deferred else ModelSwitchResult.Applied
}

internal fun modelSwitchOutcomeFromResponse(response: JSONObject): ModelSwitchResult =
    modelSwitchOutcome(
        confirmationRequired = response.optBoolean("confirm_required"),
        confirmationMessage = response.optString("confirm_message"),
        warning = response.optString("warning"),
        deferred = response.optBoolean("deferred")
    )

/**
 * Keep the pending RPC map bounded when send fails, a caller is cancelled, or a response arrives.
 * Route may remove the same entry first; compare-and-remove makes the finally block race-safe.
 */
internal suspend fun <T> awaitRpcResponse(
    pending: ConcurrentHashMap<Int, CompletableDeferred<T>>,
    id: Int,
    deferred: CompletableDeferred<T>,
    send: () -> Boolean
): T {
    pending[id] = deferred
    try {
        check(send()) { "Nie udało się wysłać ramki" }
        return deferred.await()
    } finally {
        pending.remove(id, deferred)
    }
}

/** Cancel all waiting RPCs before dropping their map entries. */
internal fun <T> cancelPendingRequests(pending: ConcurrentHashMap<Int, CompletableDeferred<T>>) {
    val waiting = pending.values.toList()
    waiting.forEach { it.cancel() }
    pending.clear()
}

/** Ignore delayed callbacks from a WebSocket connection superseded by a later connect/close. */
internal fun shouldHandleConnectionCallback(callbackEpoch: Int, currentEpoch: Int): Boolean =
    callbackEpoch == currentEpoch

internal data class ConnectionTransition<T : Any>(val epoch: Int, val displacedSocket: T?)

/** A point-in-time transport identity; a stale snapshot may never mutate a newer connection. */
internal data class ConnectionSnapshot<T : Any>(val epoch: Int, val socket: T?)

/** Atomically owns the active transport and its link state across callback and send races. */
internal class ConnectionEpochState<T : Any>(
    private val publishLinkState: (LinkState) -> Unit = {}
) {
    private var epoch = 0
    private var socket: T? = null
    private var linkState = LinkState.DOWN

    private fun setLinkState(next: LinkState) {
        linkState = next
        publishLinkState(next)
    }

    private fun matches(snapshot: ConnectionSnapshot<T>): Boolean =
        snapshot.socket != null && snapshot.epoch == epoch && socket === snapshot.socket

    @Synchronized
    fun beginConnection(): ConnectionTransition<T> {
        epoch += 1
        val displaced = socket
        socket = null
        setLinkState(LinkState.DOWN)
        return ConnectionTransition(epoch = epoch, displacedSocket = displaced)
    }

    @Synchronized
    fun currentEpoch(): Int = epoch

    @Synchronized
    fun isCurrent(expectedEpoch: Int): Boolean =
        shouldHandleConnectionCallback(expectedEpoch, epoch)

    @Synchronized
    fun currentSocket(): T? = socket

    @Synchronized
    fun currentLinkState(): LinkState = linkState

    @Synchronized
    fun snapshot(): ConnectionSnapshot<T> = ConnectionSnapshot(epoch = epoch, socket = socket)

    @Synchronized
    fun installIfCurrent(expectedEpoch: Int, candidate: T): Boolean {
        if (!shouldHandleConnectionCallback(expectedEpoch, epoch)) return false
        socket = candidate
        setLinkState(LinkState.UP)
        return true
    }

    @Synchronized
    fun clearIfCurrent(expectedEpoch: Int, candidate: T): Boolean {
        if (!shouldHandleConnectionCallback(expectedEpoch, epoch) || socket !== candidate) return false
        socket = null
        setLinkState(LinkState.DOWN)
        return true
    }

    /** Handles a failure before onOpen, when the candidate is not yet installed. */
    @Synchronized
    fun failConnectionIfCurrent(expectedEpoch: Int): Boolean {
        if (!shouldHandleConnectionCallback(expectedEpoch, epoch)) return false
        socket = null
        setLinkState(LinkState.DOWN)
        return true
    }

    /** A failure may set DOWN only if the exact send snapshot is still installed. */
    @Synchronized
    fun markSendFailedIfCurrent(snapshot: ConnectionSnapshot<T>): Boolean {
        if (!matches(snapshot)) return false
        socket = null
        setLinkState(LinkState.DOWN)
        return true
    }

    /** Validate ownership and run the nonblocking sender while that ownership cannot change. */
    @Synchronized
    fun sendIfCurrent(snapshot: ConnectionSnapshot<T>, send: (T) -> Boolean): Boolean {
        if (!matches(snapshot)) return false
        if (send(requireNotNull(socket))) return true
        socket = null
        setLinkState(LinkState.DOWN)
        return false
    }

    @Synchronized
    fun isInstalledSocket(expectedEpoch: Int, candidate: T): Boolean =
        shouldHandleConnectionCallback(expectedEpoch, epoch) && socket === candidate
}

/**
 * Klient Hermes dashboard WS JSON-RPC — port z działającego PoC (poc_gateway.py):
 * auth: token ze SPA HTML (__HERMES_SESSION_TOKEN__), WS /api/ws?token=...
 * metody: profiles.list / session.create{profile} / prompt.submit{session_id,text}
 * stream: method=event, params.type=message.delta|message.complete, params.payload.text
 */
open class GatewayClient(private val ok: OkHttpClient = OkHttpClient()) {

    private val _linkState = MutableStateFlow(LinkState.DOWN)
    open val linkState = _linkState.asStateFlow()
    private val connections = ConnectionEpochState<WebSocket> { state -> _linkState.value = state }
    private var baseUrl: String = ""
    private var basicAuth: String? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    private val _events = MutableSharedFlow<JSONObject>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    open val events = _events.asSharedFlow()

    /** Always use OkHttp's platform certificate and hostname validation. */
    private val http: OkHttpClient = ok.newBuilder()
        .addInterceptor { chain ->
            // Własny UA — Cloudflare nie lubi domyślnego "okhttp"
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "HermesBots/0.5")
                    .build()
            )
        }
        .build()

    /** Pobiera token sesji dashboardu (tryb loopback; opcjonalnie za proxy z Basic Auth). */
    private suspend fun fetchToken(
        dashboardUrl: String = baseUrl,
        authorization: String? = basicAuth
    ): String {
        val builder = Request.Builder().url("$dashboardUrl/")
        authorization?.let { builder.header("Authorization", it) }
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

    /** Atomically invalidates an older transport before starting or closing a connection. */
    private fun resetConnection(): Int {
        val transition = connections.beginConnection()
        cancelPendingRequests(pending)
        transition.displacedSocket?.close(1000, "superseded")
        return transition.epoch
    }

    /** Łączy WS i zwraca po otwarciu. Zrzuca wyjątek przy błędzie auth/połączenia. */
    open suspend fun connect(url: String, username: String = "", password: String = "") {
        val dashboardUrl = url.trimEnd('/')
        val authorization = username.takeIf { it.isNotBlank() }
            ?.let { okhttp3.Credentials.basic(it, password) }
        val callbackEpoch = resetConnection()
        baseUrl = dashboardUrl
        basicAuth = authorization
        val token = fetchToken(dashboardUrl, authorization)
        if (!connections.isCurrent(callbackEpoch)) {
            throw CancellationException("Połączenie zastąpione przez nowszą próbę")
        }
        val wsUrl = dashboardUrl.replaceFirst("http", "ws") + "/api/ws?token=" + token
        val deferred = CompletableDeferred<Unit>()
        val builder = Request.Builder().url(wsUrl)
        authorization?.let { builder.header("Authorization", it) }
        val candidate = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!connections.installIfCurrent(callbackEpoch, webSocket)) {
                    deferred.cancel()
                    webSocket.close(1000, "superseded")
                    return
                }
                deferred.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!connections.failConnectionIfCurrent(callbackEpoch)) {
                    deferred.cancel()
                    return
                }
                cancelPendingRequests(pending)
                deferred.completeExceptionally(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!connections.failConnectionIfCurrent(callbackEpoch)) {
                    deferred.cancel()
                    return
                }
                cancelPendingRequests(pending)
                deferred.cancel()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (connections.isInstalledSocket(callbackEpoch, webSocket)) route(text)
            }
        })
        if (!connections.isCurrent(callbackEpoch)) {
            candidate.cancel()
            throw CancellationException("Połączenie zastąpione przez nowszą próbę")
        }
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

    /** Await the full JSON-RPC frame so callers that need acceptance can inspect error/result. */
    private suspend fun rpcFrame(method: String, params: JSONObject): JSONObject {
        val transport = connections.snapshot()
        if (transport.socket == null) error("WS niepołączony")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("method", method).put("params", params)
        val resp = awaitRpcResponse(pending, id, deferred) {
            connections.sendIfCurrent(transport) { socket -> socket.send(msg.toString()) }
        }
        return resp
    }

    suspend fun rpc(method: String, params: JSONObject): JSONObject {
        val resp = rpcFrame(method, params)
        resp.optJSONObject("error")?.let {
            error("RPC $method: ${it.optString("message")} (${it.optString("code")})")
        }
        return resp.optJSONObject("result") ?: JSONObject()
    }

    open suspend fun listProfiles(): List<BotInfo> {
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

    suspend fun createSession(profile: String): CreatedSession {
        val res = rpc("session.create", JSONObject()
            .put("profile", profile)
            .put("title", "Hermes Bots (Android)")
            .put("source", "android-app"))
        val runtimeSessionId = res.optString("session_id").ifBlank { res.optString("sid") }
        val info = res.optJSONObject("info")
        return CreatedSession(
            handle = sessionHandleForCreate(
                runtimeSessionId = runtimeSessionId,
                storedSessionId = res.optString("stored_session_id")
            ),
            model = info?.optString("model").orEmpty(),
            provider = info?.optString("provider").orEmpty()
        )
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
    open suspend fun rosterSummary(profile: String): RosterSummary? =
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
    open suspend fun resumeSession(profile: String, sessionId: String): ResumedSession {
        val res = rpc("session.resume", JSONObject()
            .put("session_id", sessionId)
            .put("profile", profile)
            .put("cols", 80))
        val runtimeSessionId = res.optString("session_id").ifBlank { res.optString("sid") }
        val storedSessionId = res.optString("stored_session_id")
            .ifBlank { res.optString("session_key") }
            .ifBlank { res.optString("resumed") }
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
        val info = res.optJSONObject("info")
        return ResumedSession(
            handle = sessionHandleForResume(
                requestedStoredSessionId = sessionId,
                returnedRuntimeSessionId = runtimeSessionId,
                returnedStoredSessionId = storedSessionId
            ),
            messages = out,
            model = info?.optString("model").orEmpty(),
            provider = info?.optString("provider").orEmpty(),
            isRunning = res.optBoolean("running")
        )
    }

    /** Aktualny model/provider aktywnej sesji. */
    val currentModel = kotlinx.coroutines.flow.MutableStateFlow("")
    val currentProvider = kotlinx.coroutines.flow.MutableStateFlow("")

    /** Wyczyść stan przed asynchroniczną zmianą rozmowy, żeby nie pokazać starej sesji. */
    fun clearCurrentSessionModel() = syncCurrentSessionModel(model = "", provider = "")

    /** Zastosuj stan tylko wtedy, gdy ViewModel potwierdzi, że odpowiedź dotyczy aktywnej sesji. */
    fun syncCurrentSessionModel(model: String, provider: String) {
        currentModel.value = model
        currentProvider.value = provider
    }

    /** Dostępni providerzy i modele dla wskazanej sesji (model.options). */
    open suspend fun modelOptions(sessionId: String? = null): ModelOptionsPayload {
        val params = JSONObject()
        if (!sessionId.isNullOrBlank()) params.put("session_id", sessionId)
        val res = rpc("model.options", params)
        val providers = mutableListOf<ModelProviderOption>()
        val arr = res.optJSONArray("providers")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val slug = entry.optString("slug").trim()
                if (slug.isBlank()) continue
                val models = buildList {
                    entry.optJSONArray("models")?.let { rawModels ->
                        for (j in 0 until rawModels.length()) {
                            rawModels.optString(j).trim().takeIf { it.isNotEmpty() }?.let(::add)
                        }
                    }
                }
                val aliases = buildList {
                    entry.optJSONArray("aliases")?.let { rawAliases ->
                        for (j in 0 until rawAliases.length()) {
                            rawAliases.optString(j).trim().takeIf { it.isNotEmpty() }?.let(::add)
                        }
                    }
                }
                providers += ModelProviderOption(slug = slug, models = models, aliases = aliases)
            }
        }
        return ModelOptionsPayload(
            providers = providers,
            model = res.optString("model"),
            provider = res.optString("provider")
        )
    }

    /** The transport only reports the result; ViewModel applies it if this session remains active. */
    open suspend fun setSessionModel(
        sessionId: String,
        provider: String,
        model: String,
        confirmExpensiveModel: Boolean = false,
        timeoutMs: Long = MODEL_SWITCH_TIMEOUT_MS
    ): ModelSwitchResult {
        require(timeoutMs > 0) { "Model switch timeout must be positive" }
        return try {
            withTimeout(timeoutMs) {
                val command = buildSessionModelSwitchCommand(provider, model)
                val params = JSONObject()
                    .put("session_id", sessionId)
                    .put("key", "model")
                    .put("value", command)
                if (confirmExpensiveModel) params.put("confirm_expensive_model", true)
                modelSwitchOutcomeFromResponse(rpc("config.set", params))
            }
        } catch (_: TimeoutCancellationException) {
            ModelSwitchResult.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ModelSwitchResult.Failure(modelSwitchErrorForUi(e))
        }
    }

    /**
     * A prompt counts as delivered only after the gateway's matching JSON-RPC success response.
     * A local WebSocket.send() result merely proves that OkHttp accepted a frame for transport.
     */
    internal open suspend fun submitPromptResult(
        sessionId: String,
        text: String,
        queued: Boolean = false,
        acknowledgementTimeoutMs: Long = PROMPT_ACK_TIMEOUT_MS
    ): PromptSubmissionResult {
        require(acknowledgementTimeoutMs > 0) { "Prompt ACK timeout must be positive" }
        val params = JSONObject()
            .put("session_id", sessionId)
            .put("text", text)
        // Queue mode is only for the outbox drain that may race server-side terminal cleanup.
        if (queued) params.put("queued", true)
        return try {
            val response = withTimeout(acknowledgementTimeoutMs) {
                rpcFrame("prompt.submit", params)
            }
            response.optJSONObject("error")?.let { error ->
                return PromptSubmissionResult.Rejected(
                    code = error.takeIf { it.has("code") }?.optInt("code"),
                    message = error.optString("message").ifBlank {
                        "Gateway odrzucił wiadomość."
                    }
                )
            }
            val status = response.optJSONObject("result")?.optString("status").orEmpty()
            if (status == "queued" || status == "streaming") {
                PromptSubmissionResult.Accepted(status)
            } else {
                PromptSubmissionResult.Rejected(
                    code = null,
                    message = "Gateway nie potwierdził przyjęcia wiadomości."
                )
            }
        } catch (_: TimeoutCancellationException) {
            // The request could have reached the gateway. Do not replay without idempotency support.
            PromptSubmissionResult.Indeterminate
        } catch (e: CancellationException) {
            // Caller cancellation must retain normal coroutine semantics; transport reset is ambiguous.
            if (!currentCoroutineContext().isActive) throw e
            PromptSubmissionResult.Indeterminate
        } catch (e: IllegalStateException) {
            if (e.message == "WS niepołączony" || e.message == "Nie udało się wysłać ramki") {
                PromptSubmissionResult.NotSent
            } else {
                PromptSubmissionResult.Indeterminate
            }
        } catch (_: Exception) {
            PromptSubmissionResult.Indeterminate
        }
    }

    /** Compatibility API for group turns; callers that own an outbox use submitPromptResult(). */
    open suspend fun submitPrompt(sessionId: String, text: String, queued: Boolean = false): Boolean =
        submitPromptResult(sessionId, text, queued) is PromptSubmissionResult.Accepted

    fun close() {
        resetConnection()
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

    /** Resume + state for GroupChatEngine; each resume may replace both identities. */
    open suspend fun resumeSessionState(profile: String, sessionId: String): GroupChatEngine.TurnState {
        val res = rpc("session.resume", JSONObject()
            .put("session_id", sessionId).put("profile", profile).put("cols", 80))
        val runtimeSessionId = res.optString("session_id").ifBlank { res.optString("sid") }
        val storedSessionId = res.optString("stored_session_id")
            .ifBlank { res.optString("session_key") }
            .ifBlank { res.optString("resumed") }
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
        return GroupChatEngine.TurnState(
            handle = sessionHandleForResume(
                requestedStoredSessionId = sessionId,
                returnedRuntimeSessionId = runtimeSessionId,
                returnedStoredSessionId = storedSessionId
            ),
            messageCount = count,
            lastAssistantText = lastAssistant,
            inflight = inflight,
            running = res.optBoolean("running", false)
        )
    }

    /** Niskopoziomowe RPC dla GroupChatEngine. */
    open suspend fun rpcRaw(method: String, params: JSONObject): JSONObject = rpc(method, params)

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
