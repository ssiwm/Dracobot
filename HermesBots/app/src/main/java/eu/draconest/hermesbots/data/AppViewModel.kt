package eu.draconest.hermesbots.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    /** data URL obrazka (generacje) — renderowany w dymku. */
    val imageData: String? = null
) {
    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(1)
        fun nextId(): Long = counter.getAndIncrement()
    }
}

/** A response may update UI state only if it belongs to the still-active transition. */
internal fun shouldApplySessionUpdate(expectedGeneration: Int, currentGeneration: Int): Boolean =
    expectedGeneration == currentGeneration

/** A reconnect-resume response is valid only for its original active session identities and state. */
internal fun shouldApplyReconnectResult(
    expectedRuntimeSessionId: String,
    activeRuntimeSessionId: String?,
    expectedStoredSessionId: String,
    activeStoredSessionId: String?,
    expectedGeneration: Int,
    currentGeneration: Int,
    expectedRevision: Long,
    currentRevision: Long
): Boolean = expectedRuntimeSessionId == activeRuntimeSessionId &&
    expectedStoredSessionId == activeStoredSessionId &&
    shouldApplySessionUpdate(expectedGeneration, currentGeneration) &&
    expectedRevision == currentRevision

/** Sending a prompt and choosing its next-turn model intentionally have different busy rules. */
internal data class ChatActionAvailability(
    val canSubmitPrompt: Boolean,
    val canSwitchModel: Boolean
)

internal fun chatActionAvailability(
    thinking: Boolean,
    streaming: Boolean,
    modelSwitchInFlight: Boolean,
    awaitingDeferredTurnBoundary: Boolean,
    awaitingDeferredModelResolution: Boolean
): ChatActionAvailability {
    val modelTransitionBusy = modelSwitchInFlight ||
        awaitingDeferredTurnBoundary || awaitingDeferredModelResolution
    return ChatActionAvailability(
        canSubmitPrompt = !thinking && !streaming && !modelTransitionBusy,
        // The gateway queues config.set while a turn runs, so streaming alone must not hide the picker.
        canSwitchModel = !modelTransitionBusy
    )
}

/** Do not let a delayed model.options response overwrite newer same-session state. */
internal fun shouldApplyModelOptionsResult(
    expectedRuntimeSessionId: String,
    activeRuntimeSessionId: String?,
    expectedGeneration: Int,
    currentGeneration: Int,
    expectedModelStateRevision: Long,
    currentModelStateRevision: Long
): Boolean = expectedRuntimeSessionId == activeRuntimeSessionId &&
    shouldApplySessionUpdate(expectedGeneration, currentGeneration) &&
    expectedModelStateRevision == currentModelStateRevision

/** A config.set response is valid only for the exact same session, state revision, and request owner. */
internal fun shouldApplyModelSwitchResult(
    expectedRuntimeSessionId: String,
    activeRuntimeSessionId: String?,
    expectedGeneration: Int,
    currentGeneration: Int,
    expectedModelStateRevision: Long,
    currentModelStateRevision: Long,
    expectedRequestEpoch: Long,
    currentRequestEpoch: Long
): Boolean = shouldApplyModelOptionsResult(
    expectedRuntimeSessionId = expectedRuntimeSessionId,
    activeRuntimeSessionId = activeRuntimeSessionId,
    expectedGeneration = expectedGeneration,
    currentGeneration = currentGeneration,
    expectedModelStateRevision = expectedModelStateRevision,
    currentModelStateRevision = currentModelStateRevision
) && expectedRequestEpoch == currentRequestEpoch

/** A model selection accepted by config.set but queued until a later prompt starts. */
internal data class DeferredModelSwitch(
    val runtimeSessionId: String,
    val provider: String,
    val model: String,
    val confirmExpensiveModel: Boolean,
    /** True until the server turn that caused `deferred:true` has completed. */
    val awaitingCurrentTurnCompletion: Boolean,
    /** Becomes true only when this client submits a prompt after that boundary. */
    val nextTurnPromptSubmitted: Boolean = false
)

/** Build queued-switch state without trusting the local `thinking` flag. */
internal fun deferredSwitchForGatewayResponse(
    runtimeSessionId: String,
    provider: String,
    model: String,
    confirmExpensiveModel: Boolean,
    completionEpochAtRequest: Long,
    currentCompletionEpoch: Long
): DeferredModelSwitch = DeferredModelSwitch(
    runtimeSessionId = runtimeSessionId,
    provider = provider,
    model = model,
    confirmExpensiveModel = confirmExpensiveModel,
    awaitingCurrentTurnCompletion = currentCompletionEpoch == completionEpochAtRequest
)

/** Consume only the known original turn boundary; an unrelated session leaves state intact. */
internal fun markDeferredSwitchCompletion(
    pending: DeferredModelSwitch,
    activeRuntimeSessionId: String?
): DeferredModelSwitch = if (
    pending.runtimeSessionId == activeRuntimeSessionId && pending.awaitingCurrentTurnCompletion
) {
    pending.copy(awaitingCurrentTurnCompletion = false)
} else {
    pending
}

/** Mark the first prompt submitted after the original deferred turn has ended. */
internal fun markDeferredSwitchPromptSubmitted(
    pending: DeferredModelSwitch,
    activeRuntimeSessionId: String?
): DeferredModelSwitch = if (
    pending.runtimeSessionId == activeRuntimeSessionId &&
    !pending.awaitingCurrentTurnCompletion &&
    !pending.nextTurnPromptSubmitted
) {
    pending.copy(nextTurnPromptSubmitted = true)
} else {
    pending
}

/** Rebind a deferred request after reconnect; an idle authoritative resume supplies the missing boundary. */
internal fun reconcileDeferredBoundaryAfterResume(
    pending: DeferredModelSwitch,
    activeRuntimeSessionId: String,
    serverRunning: Boolean
): DeferredModelSwitch {
    val rebound = pending.copy(runtimeSessionId = activeRuntimeSessionId)
    return if (serverRunning) rebound else markDeferredSwitchCompletion(rebound, activeRuntimeSessionId)
}

internal enum class DeferredResumeReconciliation { Waiting, Applied, Failed }

/** Resolve a deferred next turn after reconnect only from the server's authoritative idle state. */
internal fun deferredResumeReconciliation(
    pending: DeferredModelSwitch,
    serverRunning: Boolean,
    actualProvider: String,
    actualModel: String
): DeferredResumeReconciliation {
    if (serverRunning || pending.awaitingCurrentTurnCompletion || !pending.nextTurnPromptSubmitted) {
        return DeferredResumeReconciliation.Waiting
    }
    return if (pending.provider == actualProvider && pending.model == actualModel) {
        DeferredResumeReconciliation.Applied
    } else {
        DeferredResumeReconciliation.Failed
    }
}

/** A model.options refresh is safe only after that client-submitted next turn completes. */
internal fun shouldReconcileDeferredSwitch(
    pending: DeferredModelSwitch,
    activeRuntimeSessionId: String?
): Boolean = pending.runtimeSessionId == activeRuntimeSessionId &&
    !pending.awaitingCurrentTurnCompletion && pending.nextTurnPromptSubmitted

/** Gateway error events do not carry a typed confirmation discriminator, so never retry from text alone. */
internal enum class DeferredErrorAction { Ignore, ReconcileAuthoritatively }

internal fun deferredErrorAction(
    pending: DeferredModelSwitch?,
    activeRuntimeSessionId: String?
): DeferredErrorAction = if (
    pending != null && pending.runtimeSessionId == activeRuntimeSessionId &&
    (pending.awaitingCurrentTurnCompletion || shouldReconcileDeferredSwitch(pending, activeRuntimeSessionId))
) {
    DeferredErrorAction.ReconcileAuthoritatively
} else {
    DeferredErrorAction.Ignore
}

/** Whether a persisted outbox item is safe to submit automatically. */
internal enum class QueuedPromptDeliveryState { Pending, Rejected, Indeterminate }

/** An offline prompt is bound to the durable session key, never whichever chat becomes active later. */
internal data class QueuedPrompt(
    val id: String = java.util.UUID.randomUUID().toString(),
    val storedSessionId: String,
    /** Optional for legacy snapshots; new entries bind to the bot that owns the durable session. */
    val profileName: String? = null,
    val text: String,
    val deliveryState: QueuedPromptDeliveryState = QueuedPromptDeliveryState.Pending,
    /** Human-readable gateway reason for an explicitly rejected delivery; absent for legacy entries. */
    val deliveryDetail: String? = null,
    /** Wall-clock enqueue time for local aggregate diagnostics; 0 means legacy/unknown. */
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

internal fun canFlushSessionOutbox(
    awaitingDeferredTurnBoundary: Boolean,
    awaitingDeferredModelResolution: Boolean
): Boolean = !awaitingDeferredTurnBoundary && !awaitingDeferredModelResolution

/** A direct submit may proceed only while this exact durable entry is still Pending and owned. */
internal fun canSubmitOutboxEntry(current: QueuedPrompt?, expected: QueuedPrompt): Boolean =
    current?.id == expected.id && current.deliveryState == QueuedPromptDeliveryState.Pending

/**
 * FIFO prompts scoped to a durable session. A write to a WebSocket is not an ACK:
 * callers must acknowledge the exact entry only after gateway RPC acceptance.
 */
internal class SessionOutbox(initialEntries: List<QueuedPrompt> = emptyList()) {
    private val entries = initialEntries.toMutableList()

    val size: Int
        @Synchronized get() = entries.size

    @Synchronized
    fun snapshot(): List<QueuedPrompt> = entries.toList()

    @Synchronized
    fun entryById(id: String): QueuedPrompt? = entries.firstOrNull { it.id == id }

    @Synchronized
    fun restore(snapshot: List<QueuedPrompt>) {
        entries.clear()
        entries += snapshot
    }

    @Synchronized
    fun enqueue(storedSessionId: String, text: String, profileName: String? = null): QueuedPrompt {
        val durableId = storedSessionId.trim()
        require(durableId.isNotEmpty()) { "Outbox requires a durable session key" }
        val normalizedProfile = profileName?.trim()?.ifBlank { null }
        return QueuedPrompt(
            storedSessionId = durableId,
            profileName = normalizedProfile,
            text = text
        ).also { entries += it }
    }


    /**
     * Auto-delivery scopes are bound to an authenticated profile and its durable session key.
     * Legacy entries without a profile intentionally never match this API.
     */
    @Synchronized
    fun headFor(profileName: String, storedSessionId: String): QueuedPrompt? {
        val profile = profileName.trim()
        val stored = storedSessionId.trim()
        if (profile.isEmpty() || stored.isEmpty()) return null
        return entries.firstOrNull { it.profileName == profile && it.storedSessionId == stored }
    }

    /** A direct submit is permitted only for the oldest entry in this durable-session FIFO. */
    @Synchronized
    fun isHead(entry: QueuedPrompt): Boolean =
        entry.profileName != null && entries.firstOrNull {
            it.profileName == entry.profileName && it.storedSessionId == entry.storedSessionId
        }?.id == entry.id

    /**
     * Returns the oldest automatically-sendable prompt for this exact durable session.
     * A rejected or ambiguous oldest item deliberately blocks later entries to preserve FIFO.
     */

    /** Returns an auto-sendable prompt only for the exact bound profile/session pair. */
    @Synchronized
    fun nextFor(profileName: String, storedSessionId: String): QueuedPrompt? =
        headFor(profileName, storedSessionId)?.takeIf { it.deliveryState == QueuedPromptDeliveryState.Pending }

    /** Removes exactly the prompt whose delivery was acknowledged by the gateway. */
    @Synchronized
    fun acknowledge(entry: QueuedPrompt): Boolean = entries.removeAll { it.id == entry.id }

    /** Keeps a known-rejected prompt for explicit user resolution; never silently replay it. */
    @Synchronized
    fun reject(entry: QueuedPrompt, detail: String? = null): Boolean = updateDeliveryState(
        entry,
        QueuedPromptDeliveryState.Rejected,
        detail
    )

    /** A sent frame without an ACK may have reached the gateway, so replay would risk duplication. */
    @Synchronized
    fun markIndeterminate(entry: QueuedPrompt): Boolean = updateDeliveryState(
        entry,
        QueuedPromptDeliveryState.Indeterminate
    )

    /**
     * A user explicitly chose to create a fresh delivery attempt. The original retained
     * entry is replaced in-place with a new identity, preserving durable FIFO ordering.
     */
    @Synchronized
    fun requeueAsNew(entry: QueuedPrompt): QueuedPrompt? {
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index < 0) return null
        val current = entries[index]
        if (current.deliveryState == QueuedPromptDeliveryState.Pending) return null
        return current.copy(
            id = java.util.UUID.randomUUID().toString(),
            deliveryState = QueuedPromptDeliveryState.Pending,
            deliveryDetail = null,
            createdAtEpochMillis = System.currentTimeMillis()
        ).also { entries[index] = it }
    }

    /** Explicit user discard: never affects an entry from another durable session. */
    @Synchronized
    fun discard(entryId: String): QueuedPrompt? {
        val index = entries.indexOfFirst { it.id == entryId }
        return if (index < 0) null else entries.removeAt(index)
    }

    private fun updateDeliveryState(
        entry: QueuedPrompt,
        state: QueuedPromptDeliveryState,
        detail: String? = null
    ): Boolean {
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index < 0) return false
        entries[index] = entries[index].copy(
            deliveryState = state,
            deliveryDetail = detail?.trim()?.ifBlank { null }
        )
        return true
    }


    /** Rebind a continuation successor only in the authoritative profile's bound scope. */
    @Synchronized
    fun rebindStoredSession(
        profileName: String,
        previousStoredSessionId: String,
        resumedStoredSessionId: String
    ): Int {
        val profile = profileName.trim()
        val previous = previousStoredSessionId.trim()
        val resumed = resumedStoredSessionId.trim()
        if (profile.isEmpty() || previous.isEmpty() || resumed.isEmpty() || previous == resumed) return 0
        var moved = 0
        entries.replaceAll { entry ->
            if (entry.profileName == profile && entry.storedSessionId == previous) {
                moved += 1
                entry.copy(storedSessionId = resumed)
            } else {
                entry
            }
        }
        return moved
    }
}

private const val VIEW_MODEL_MODEL_SWITCH_TIMEOUT_MS = 12_000L

class AppViewModel(
    private val client: GatewayClient = GatewayClient(),
    private val modelSwitchTimeoutMs: Long = VIEW_MODEL_MODEL_SWITCH_TIMEOUT_MS
) : ViewModel() {
    init {
        require(modelSwitchTimeoutMs > 0) { "Model switch timeout must be positive" }
    }
    lateinit var store: AppStore

    /** Must be called before connection work so persisted outbox entries survive process recreation. */
    fun initializeStore(appStore: AppStore) {
        store = appStore
        outbox.restore(appStore.queuedPrompts)
        publishOutboxSnapshot()
    }

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

    /** Zmienia model bieżącej rozmowy w zakresie tej sesji. */
    suspend fun switchModel(
        provider: String,
        model: String,
        confirmExpensiveModel: Boolean = false
    ): ModelSwitchResult {
        val sid = sessionId ?: return ModelSwitchResult.Failure("Brak aktywnej rozmowy")
        val generation = sessionGeneration
        val expectedModelStateRevision = modelStateRevision
        val requestEpoch = ++modelSwitchRequestEpoch
        val completionEpochAtRequest = completedTurnEpoch
        modelSwitchInFlight.value = true
        try {
            val outcome = try {
                kotlinx.coroutines.withTimeout(modelSwitchTimeoutMs) {
                    client.setSessionModel(sid, provider, model, confirmExpensiveModel)
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                ModelSwitchResult.TimedOut
            }
            if (!shouldApplyModelSwitchResult(
                    expectedRuntimeSessionId = sid,
                    activeRuntimeSessionId = sessionId,
                    expectedGeneration = generation,
                    currentGeneration = sessionGeneration,
                    expectedModelStateRevision = expectedModelStateRevision,
                    currentModelStateRevision = modelStateRevision,
                    expectedRequestEpoch = requestEpoch,
                    currentRequestEpoch = modelSwitchRequestEpoch
                )
            ) {
                return ModelSwitchResult.Failure("Zmiana modelu została zastąpiona nowszym stanem sesji.")
            }
            when (outcome) {
                ModelSwitchResult.Applied -> {
                    setDeferredModelSwitch(null)
                    syncCurrentChatModel(model.trim(), provider.trim())
                }
                ModelSwitchResult.Deferred -> {
                    setDeferredModelSwitch(
                        deferredSwitchForGatewayResponse(
                            runtimeSessionId = sid,
                            provider = provider.trim(),
                            model = model.trim(),
                            confirmExpensiveModel = confirmExpensiveModel,
                            completionEpochAtRequest = completionEpochAtRequest,
                            currentCompletionEpoch = completedTurnEpoch
                        )
                    )
                    markChatStateMutation()
                }
                ModelSwitchResult.TimedOut -> {
                    // The gateway may have applied config.set after our deadline; read, never retry.
                    reconcileTimedOutModelSwitch(
                        runtimeSessionId = sid,
                        generation = generation,
                        expectedModelStateRevision = expectedModelStateRevision,
                        requestEpoch = requestEpoch
                    )
                }
                else -> setDeferredModelSwitch(null)
            }
            return outcome
        } finally {
            if (modelSwitchRequestEpoch == requestEpoch) {
                modelSwitchInFlight.value = false
            }
        }
    }

    /** A timed-out config.set is ambiguous: query current state once, guarded, and never resubmit it. */
    private fun reconcileTimedOutModelSwitch(
        runtimeSessionId: String,
        generation: Int,
        expectedModelStateRevision: Long,
        requestEpoch: Long
    ) {
        viewModelScope.launch {
            val payload = try {
                kotlinx.coroutines.withTimeout(8_000) {
                    client.modelOptions(runtimeSessionId)
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (shouldApplyModelSwitchResult(
                    expectedRuntimeSessionId = runtimeSessionId,
                    activeRuntimeSessionId = sessionId,
                    expectedGeneration = generation,
                    currentGeneration = sessionGeneration,
                    expectedModelStateRevision = expectedModelStateRevision,
                    currentModelStateRevision = modelStateRevision,
                    expectedRequestEpoch = requestEpoch,
                    currentRequestEpoch = modelSwitchRequestEpoch
                )
            ) {
                syncCurrentChatModel(payload.model, payload.provider)
            }
        }
    }

    /** Lista provider/modeli dla aktywnej sesji oraz synchronizacja jej autorytatywnej tożsamości. */
    suspend fun loadModelOptions(): List<ModelProviderOption> {
        val sid = sessionId ?: return emptyList()
        val generation = sessionGeneration
        val expectedModelStateRevision = modelStateRevision
        val payload = client.modelOptions(sid)
        if (shouldApplyModelOptionsResult(
                expectedRuntimeSessionId = sid,
                activeRuntimeSessionId = sessionId,
                expectedGeneration = generation,
                currentGeneration = sessionGeneration,
                expectedModelStateRevision = expectedModelStateRevision,
                currentModelStateRevision = modelStateRevision
            )
        ) {
            syncCurrentChatModel(payload.model, payload.provider)
        }
        return payload.providers
    }

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
                    markChatStateMutation()
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

    // ---- Generowanie obrazów ----

    data class GeneratedImage(val dataUrl: String, val prompt: String)

    /** Ostatni wygenerowany obraz (do pokazania w czacie). */
    val lastGenerated = MutableStateFlow<GeneratedImage?>(null)
    val generatingImage = MutableStateFlow(false)

    /**
     * Wygeneruj obraz (image.generate). Zwraca komunikat bledu lub null.
     * Wynik trafia do lastGenerated i jako wiadomosc w czacie.
     */
    suspend fun generateImage(prompt: String, aspect: String = "square"): String? {
        if (prompt.isBlank()) return "Pusty prompt"
        generatingImage.value = true
        try {
            val res = client.rpcRaw("image.generate", org.json.JSONObject()
                .put("prompt", prompt.trim())
                .put("aspect_ratio", aspect))
            if (!res.optBoolean("available", true)) {
                return "Generowanie obrazów nie jest skonfigurowane na serwerze. Dodaj FAL_KEY do .env lub ustaw image_gen.provider przez `hermes tools`."
            }
            if (!res.optBoolean("success", false)) {
                return res.optString("error").ifBlank { "Generowanie nie powiodło się" }
            }
            val dataUrl = res.optString("image_data").ifBlank { "" }
            if (dataUrl.isBlank()) {
                // brak data URL — jest moze URL/path; pokaz info
                return "Obraz wygenerowany, ale nie udało się go pobrać: ${res.optString("image")}"
            }
            val img = GeneratedImage(dataUrl, prompt.trim())
            lastGenerated.value = img
            messages.value += ChatMessage(
                ChatMessage.nextId(), fromUser = false,
                text = "🎨 Wygenerowano: „${prompt.trim()}”",
                imageData = dataUrl
            )
            markChatStateMutation()
            return null
        } catch (e: Exception) {
            return e.message ?: "Błąd generowania"
        } finally {
            generatingImage.value = false
        }
    }
    /** Czy regeneracja jest mozliwa: jest poprzednia wiadomosc usera i bot nie mysli. */
    fun canRegenerate(): Boolean =
       !thinking.value && messages.value.lastOrNull()?.streaming != true &&
           !modelSwitchInFlight.value && !awaitingDeferredTurnBoundary.value &&
           !awaitingDeferredModelResolution.value &&
           messages.value.any { it.fromUser } &&
           messages.value.lastOrNull()?.fromUser == false

    /**
    * Regeneruj ostatnia odpowiedz bota: ponawia ostatni prompt usera
    * (serwer doklada nowa odpowiedz do historii).
    */
    fun regenerateLast() {
       if (!canRegenerate()) return
       val lastUser = messages.value.lastOrNull { it.fromUser }?.text ?: return
       // nowa tura: czysc podglad myslenia (jak w send)
       thinkingText.value = ""
       statusText.value = ""
       thinkingHasContent.value = false
       thinkingOpen.value = true
       val sid = sessionId ?: return
       val durableSessionId = storedSessionId ?: return
       val generation = sessionGeneration
       val regenerationPrompt = "Powtórz poprzednie zadanie, odpowiedz inaczej/lepiej. Zadanie: $lastUser"
       val profileName = activeBot.value?.name ?: return
       val entry = outbox.enqueue(durableSessionId, regenerationPrompt, profileName)
       persistOutbox()
       thinking.value = true
       viewModelScope.launch {
           outboxFlushMutex.withLock {
               if (!canSubmitOutboxEntry(outbox.entryById(entry.id), entry)) return@withLock
               if (deferBehindEarlierOutboxEntry(entry)) return@withLock
               when (val result = client.submitPromptResult(sid, entry.text)) {
                   is PromptSubmissionResult.Accepted -> {
                       outbox.acknowledge(entry)
                       persistOutbox()
                       if (isCurrentPromptScope(sid, durableSessionId, generation)) {
                           notePromptSubmitted()
                           messages.value += ChatMessage(ChatMessage.nextId(), fromUser = true, text = lastUser)
                           markChatStateMutation()
                       }
                   }
                   PromptSubmissionResult.NotSent -> {
                       if (isCurrentPromptScope(sid, durableSessionId, generation)) {
                           thinking.value = false
                           appendPromptDeliveryNotice("Wiadomość oczekuje na ponowne połączenie.")
                       }
                       offline.value = true
                       quietReconnectLoop()
                   }
                   is PromptSubmissionResult.Rejected -> {
                       outbox.reject(entry, result.message)
                       persistOutbox()
                       if (isCurrentPromptScope(sid, durableSessionId, generation)) {
                           thinking.value = false
                           appendPromptDeliveryNotice(result.message)
                       }
                   }
                   PromptSubmissionResult.Indeterminate -> {
                       outbox.markIndeterminate(entry)
                       persistOutbox()
                       if (isCurrentPromptScope(sid, durableSessionId, generation)) {
                           thinking.value = false
                           appendPromptDeliveryNotice(
                               "Nie można potwierdzić wysłania. Nie wyślę go ponownie automatycznie, aby uniknąć duplikatu."
                           )
                       }
                       offline.value = true
                       quietReconnectLoop()
                   }
               }
           }
       }
    }

    val sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    /** "offline" = zalogowani, ale WS padl (apka w tle itp.) */
    val offline = MutableStateFlow(false)

    /** Runtime id routes WS RPC/events and is replaced by every session.resume. */
    private var sessionId: String? = null
    /** Durable key is the only identifier persisted and accepted by session.resume. */
    private var storedSessionId: String? = null
    private var sessionGeneration = 0
    /** Bumps for every visible/authoritative chat mutation; reconnect snapshots must not overwrite newer state. */
    private var chatStateRevision = 0L
    /** Bumps for every model/provider write; delayed model.options must not overwrite session.info. */
    private var modelStateRevision = 0L
    /** Monotonic ownership token for config.set requests in this ViewModel. */
    private var modelSwitchRequestEpoch = 0L
    /** Monotonic counter of message.complete events for the current process. */
    private var completedTurnEpoch = 0L
    val modelSwitchInFlight = MutableStateFlow(false)
    val awaitingDeferredTurnBoundary = MutableStateFlow(false)
    /** Keeps a second prompt from racing the authoritative post-turn reconciliation. */
    val awaitingDeferredModelResolution = MutableStateFlow(false)
    private var deferredModelSwitch: DeferredModelSwitch? = null

    private fun setDeferredModelSwitch(pending: DeferredModelSwitch?) {
        deferredModelSwitch = pending
        awaitingDeferredTurnBoundary.value = pending?.awaitingCurrentTurnCompletion == true
        awaitingDeferredModelResolution.value = pending?.nextTurnPromptSubmitted == true
    }

    private fun notePromptSubmitted() {
        val sid = sessionId ?: return
        val pending = deferredModelSwitch ?: return
        val advanced = markDeferredSwitchPromptSubmitted(pending, sid)
        if (advanced != pending) setDeferredModelSwitch(advanced)
    }

    /** Never apply a delayed prompt acknowledgement to a chat that has since changed identity. */
    private fun isCurrentPromptScope(
        expectedRuntimeSessionId: String,
        expectedStoredSessionId: String,
        expectedGeneration: Int
    ): Boolean = sessionId == expectedRuntimeSessionId &&
        storedSessionId == expectedStoredSessionId &&
        sessionGeneration == expectedGeneration

    private fun appendPromptDeliveryNotice(message: String) {
        messages.value += ChatMessage(ChatMessage.nextId(), fromUser = false, text = "⚠️ $message")
        markChatStateMutation()
    }

    private fun markChatStateMutation() {
        chatStateRevision += 1
    }

    private fun syncCurrentChatModel(model: String, provider: String) {
        client.syncCurrentSessionModel(model, provider)
        modelStateRevision += 1
        markChatStateMutation()
    }

    private fun clearCurrentChatModel() {
        client.clearCurrentSessionModel()
        modelStateRevision += 1
        markChatStateMutation()
    }

    /** Invalidate every in-flight session request before opening/resuming another chat. */
    private fun beginSessionTransition(): Int {
        sessionGeneration += 1
        modelSwitchRequestEpoch += 1
        modelSwitchInFlight.value = false
        sessionId = null
        storedSessionId = null
        setDeferredModelSwitch(null)
        clearCurrentChatModel()
        return sessionGeneration
    }

    private fun activateSession(
        generation: Int,
        handle: SessionHandle,
        model: String,
        provider: String,
        requestedStoredSessionId: String? = null
    ): Boolean {
        if (!shouldApplySessionUpdate(generation, sessionGeneration)) return false
        sessionId = handle.runtimeSessionId
        storedSessionId = handle.storedSessionId
        requestedStoredSessionId?.let { previousStoredSessionId ->
            rebindOutboxAfterAuthoritativeResume(previousStoredSessionId, handle.storedSessionId)
        }
        if (::store.isInitialized) store.lastSessionId = handle.storedSessionId
        syncCurrentChatModel(model, provider)
        return true
    }

    /** Reconcile only after the client submitted the post-boundary turn and it reached a terminal event. */
    private fun reconcileDeferredModelSwitchAfterTerminalEvent() {
        val pending = deferredModelSwitch ?: return
        val sid = sessionId ?: return
        if (pending.runtimeSessionId != sid || pending.awaitingCurrentTurnCompletion) return
        if (!shouldReconcileDeferredSwitch(pending, sid)) return
        val generation = sessionGeneration
        val expectedModelStateRevision = modelStateRevision
        viewModelScope.launch {
            try {
                val payload = client.modelOptions(sid)
                if (!shouldApplyModelOptionsResult(
                        expectedRuntimeSessionId = sid,
                        activeRuntimeSessionId = sessionId,
                        expectedGeneration = generation,
                        currentGeneration = sessionGeneration,
                        expectedModelStateRevision = expectedModelStateRevision,
                        currentModelStateRevision = modelStateRevision
                    ) || deferredModelSwitch != pending
                ) return@launch
                setDeferredModelSwitch(null)
                syncCurrentChatModel(payload.model, payload.provider)
                if (payload.model != pending.model || payload.provider != pending.provider) {
                    messages.value += ChatMessage(
                        ChatMessage.nextId(),
                        fromUser = false,
                        text = "⚠️ Odroczona zmiana modelu nie została zastosowana przez gateway."
                    )
                    markChatStateMutation()
                }
                flushOutbox()
            } catch (_: Exception) {
                if (shouldApplyModelOptionsResult(
                        expectedRuntimeSessionId = sid,
                        activeRuntimeSessionId = sessionId,
                        expectedGeneration = generation,
                        currentGeneration = sessionGeneration,
                        expectedModelStateRevision = expectedModelStateRevision,
                        currentModelStateRevision = modelStateRevision
                    ) && deferredModelSwitch == pending
                ) {
                    setDeferredModelSwitch(null)
                    messages.value += ChatMessage(
                        ChatMessage.nextId(),
                        fromUser = false,
                        text = "⚠️ Nie udało się potwierdzić odroczonej zmiany modelu. Wybierz model ponownie."
                    )
                    markChatStateMutation()
                    flushOutbox()
                }
            }
        }
    }

    /** A bare gateway error has no reliable terminal discriminator; resume decides its boundary. */
    private fun reconcileDeferredErrorThroughAuthoritativeResume() {
        val pending = deferredModelSwitch ?: return
        val sid = sessionId ?: return
        if (pending.runtimeSessionId != sid) return
        viewModelScope.launch {
            refreshCurrentChatAfterReconnect()
            flushOutbox()
        }
    }

    private var eventsJob: Job? = null
    private var linkWatchJob: Job? = null
    private var reconnectJob: Job? = null
    private val outbox = SessionOutbox() // durable-session-scoped prompts waiting for a usable link
    private val _outboxEntries = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    internal val outboxEntries = _outboxEntries.asStateFlow()
    private val outboxFlushMutex = Mutex()

    private fun publishOutboxSnapshot() {
        _outboxEntries.value = outbox.snapshot()
    }

    private fun persistOutbox() {
        val snapshot = outbox.snapshot()
        _outboxEntries.value = snapshot
        if (::store.isInitialized) store.queuedPrompts = snapshot
    }

    /** Removes an entry only after an explicit user choice from the Outbox Center. */
    fun discardOutboxEntry(entryId: String) {
        if (outbox.discard(entryId) != null) persistOutbox()
    }

    /**
     * Explicit resend creates a new entry identity. It is never an automatic retry of an
     * unacknowledged frame; it can drain only in the matching active profile/session.
     */
    fun resendOutboxEntryAsNew(entryId: String) {
        val held = outbox.entryById(entryId) ?: return
        val profileName = held.profileName ?: return
        val replacement = outbox.requeueAsNew(held) ?: return
        persistOutbox()
        val matchingActiveProfile = profileName == activeBot.value?.name
        if (matchingActiveProfile && replacement.storedSessionId == storedSessionId) {
            flushOutbox()
        }
    }

    /** Move only the exact durable key that session.resume authoritatively resolved to a successor. */
    private fun rebindOutboxAfterAuthoritativeResume(
        requestedStoredSessionId: String,
        resumedStoredSessionId: String
    ) {
        val profileName = activeBot.value?.name ?: return
        if (outbox.rebindStoredSession(profileName, requestedStoredSessionId, resumedStoredSessionId) > 0) {
            persistOutbox()
        }
    }

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
        val activeRuntimeSessionId = sessionId ?: return
        val storedSessionFallback = if (::store.isInitialized) {
            store.lastSessionId.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val activeStoredSessionId = storedSessionId ?: storedSessionFallback ?: return
        val reconnectGeneration = sessionGeneration
        val reconnectRevision = chatStateRevision
        try {
            val resumed = kotlinx.coroutines.withTimeout(15_000) {
                client.resumeSession(bot.name, activeStoredSessionId)
            }
            if (!shouldApplyReconnectResult(
                expectedRuntimeSessionId = activeRuntimeSessionId,
                activeRuntimeSessionId = sessionId,
                expectedStoredSessionId = activeStoredSessionId,
                activeStoredSessionId = storedSessionId ?: storedSessionFallback,
                expectedGeneration = reconnectGeneration,
                currentGeneration = sessionGeneration,
                expectedRevision = reconnectRevision,
                currentRevision = chatStateRevision
            )) return
            rebindOutboxAfterAuthoritativeResume(
                requestedStoredSessionId = activeStoredSessionId,
                resumedStoredSessionId = resumed.handle.storedSessionId
            )
            sessionId = resumed.handle.runtimeSessionId
            storedSessionId = resumed.handle.storedSessionId
            if (::store.isInitialized) store.lastSessionId = resumed.handle.storedSessionId
            val deferredFailureAfterResume = deferredModelSwitch
                ?.takeIf { it.runtimeSessionId == activeRuntimeSessionId }
                ?.let { pending ->
                    val rebound = reconcileDeferredBoundaryAfterResume(
                        pending = pending,
                        activeRuntimeSessionId = resumed.handle.runtimeSessionId,
                        serverRunning = resumed.isRunning
                    )
                    if (pending.awaitingCurrentTurnCompletion && !rebound.awaitingCurrentTurnCompletion) {
                        completedTurnEpoch += 1
                    }
                    when (
                        deferredResumeReconciliation(
                            pending = rebound,
                            serverRunning = resumed.isRunning,
                            actualProvider = resumed.provider,
                            actualModel = resumed.model
                        )
                    ) {
                        DeferredResumeReconciliation.Applied -> {
                            setDeferredModelSwitch(null)
                            false
                        }
                        DeferredResumeReconciliation.Failed -> {
                            setDeferredModelSwitch(null)
                            true
                        }
                        DeferredResumeReconciliation.Waiting -> {
                            setDeferredModelSwitch(rebound)
                            false
                        }
                    }
                } ?: false
            val restored = resumed.messages.map { (fromUser, text) ->
                ChatMessage(ChatMessage.nextId(), fromUser, text)
            }
            syncCurrentChatModel(resumed.model, resumed.provider)
            // nie nadpisuj, jesli user cos wpisal lokalnie, czego nie ma w historii
            val localOnly = messages.value.any { msg ->
                msg.fromUser && restored.none { r -> r.fromUser && r.text == msg.text }
            }
            if (!localOnly && restored.isNotEmpty()) {
                messages.value = restored
            }
            if (deferredFailureAfterResume) {
                messages.value += ChatMessage(
                    ChatMessage.nextId(),
                    fromUser = false,
                    text = "⚠️ Odroczona zmiana modelu nie została zastosowana podczas reconnectu. Wybierz model ponownie."
                )
            }
            thinking.value = resumed.isRunning // session.resume is authoritative for the still-running turn
            markChatStateMutation()
        } catch (_: Exception) {
            // sesja mogla wygasnac — zostaw co jest, user otworzy z listy
        }
    }

    /**
     * A new entry must not overtake a retained predecessor in the same durable-session FIFO.
     * Pending predecessors are drained with queued=true; rejected/ambiguous predecessors remain a visible stop.
     */
    private suspend fun deferBehindEarlierOutboxEntry(entry: QueuedPrompt): Boolean {
        if (outbox.isHead(entry)) return false
        val profileName = entry.profileName ?: return true
        when (outbox.headFor(profileName, entry.storedSessionId)?.deliveryState) {
            QueuedPromptDeliveryState.Pending -> flushOutboxNow()
            QueuedPromptDeliveryState.Rejected,
            QueuedPromptDeliveryState.Indeterminate -> {
                thinking.value = false
                appendPromptDeliveryNotice(
                    "Wiadomość została zapisana za wcześniejszą pozycją wymagającą potwierdzenia."
                )
            }
            null -> return false
        }
        return true
    }

    internal fun flushOutbox() {
        viewModelScope.launch {
            outboxFlushMutex.withLock { flushOutboxNow() }
        }
    }

    private suspend fun flushOutboxNow() {
        if (!canFlushSessionOutbox(
                awaitingDeferredTurnBoundary = awaitingDeferredTurnBoundary.value,
                awaitingDeferredModelResolution = awaitingDeferredModelResolution.value
            )
        ) return
        val sid = sessionId ?: return
        val durableSessionId = storedSessionId ?: return
        val profileName = activeBot.value?.name ?: return
        val generation = sessionGeneration
        val entry = outbox.nextFor(profileName, durableSessionId) ?: return
        when (val result = client.submitPromptResult(sid, entry.text, queued = true)) {
            is PromptSubmissionResult.Accepted -> {
                outbox.acknowledge(entry)
                persistOutbox()
                if (isCurrentPromptScope(sid, durableSessionId, generation)) {
                    messages.value += ChatMessage(ChatMessage.nextId(), fromUser = true, text = entry.text)
                    notePromptSubmitted()
                    thinking.value = true
                    markChatStateMutation()
                }
                // Exactly one ACKed prompt per flush: the next turn must reach a terminal state first.
            }
            is PromptSubmissionResult.Rejected -> {
                outbox.reject(entry, result.message)
                persistOutbox()
                if (isCurrentPromptScope(sid, durableSessionId, generation)) {
                    appendPromptDeliveryNotice(result.message)
                }
            }
            PromptSubmissionResult.NotSent -> {
                offline.value = true
                quietReconnectLoop()
            }
            PromptSubmissionResult.Indeterminate -> {
                outbox.markIndeterminate(entry)
                persistOutbox()
                if (isCurrentPromptScope(sid, durableSessionId, generation)) {
                    appendPromptDeliveryNotice(
                        "Nie można potwierdzić kolejki wiadomości. Nie wyślę jej ponownie automatycznie, aby uniknąć duplikatu."
                    )
                }
                offline.value = true
                quietReconnectLoop()
            }
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
        val generation = beginSessionTransition()
        val lastSession = store.lastSessionId
        if (lastSession.isNotBlank()) {
            try {
                val resumed = client.resumeSession(bot.name, lastSession)
                if (!activateSession(
                        generation = generation,
                        handle = resumed.handle,
                        model = resumed.model,
                        provider = resumed.provider,
                        requestedStoredSessionId = lastSession
                    )) return
                messages.value = resumed.messages.map { (fromUser, text) ->
                    ChatMessage(ChatMessage.nextId(), fromUser, text)
                }
                return
            } catch (_: Exception) {
                // sesja wygasla/zarchiwizowana -> pokaz wybor
            }
        }
        val list = client.listSessions(bot.name).sortedByDescending { it.startedAt }
        if (!shouldApplySessionUpdate(generation, sessionGeneration)) return
        if (list.isEmpty()) {
            val created = client.createSession(bot.name)
            activateSession(generation, created.handle, created.model, created.provider)
        } else {
            sessions.value = list
        }
    }

    private fun subscribeEvents() {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            client.events.collect { p ->
                val eventSessionId = p.optString("session_id")
                if (eventSessionId.isNotBlank() && eventSessionId != sessionId) return@collect
                when (p.optString("type")) {
                    "session.info" -> {
                        val info = p.optJSONObject("payload") ?: return@collect
                        val model = info.optString("model")
                        val provider = info.optString("provider")
                        if (model.isNotBlank() || provider.isNotBlank()) {
                            val pending = deferredModelSwitch
                            if (pending != null &&
                                shouldReconcileDeferredSwitch(pending, sessionId) &&
                                pending.model == model && pending.provider == provider
                            ) {
                                setDeferredModelSwitch(null)
                            }
                            syncCurrentChatModel(model, provider)
                        }
                    }
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
                        completedTurnEpoch += 1
                        val pending = deferredModelSwitch
                        if (pending?.awaitingCurrentTurnCompletion == true) {
                            setDeferredModelSwitch(markDeferredSwitchCompletion(pending, sessionId))
                            flushOutbox()
                        } else {
                            reconcileDeferredModelSwitchAfterTerminalEvent()
                            flushOutbox()
                        }
                    }
                    "error" -> {
                        val message = p.optJSONObject("payload")?.optString("message")
                            .orEmpty()
                            .ifBlank { p.optString("message") }
                        if (message.isNotBlank()) {
                            messages.value += ChatMessage(
                                ChatMessage.nextId(),
                                fromUser = false,
                                text = "⚠️ $message"
                            )
                            markChatStateMutation()
                        }
                        when (deferredErrorAction(deferredModelSwitch, sessionId)) {
                            DeferredErrorAction.ReconcileAuthoritatively ->
                                reconcileDeferredErrorThroughAuthoritativeResume()
                            DeferredErrorAction.Ignore -> Unit
                        }
                    }
                    "reasoning.delta" -> {
                        // przyrost tekstu rozumowania (jak w CLI: _reasoning_buf += text)
                        val t = p.optJSONObject("payload")?.optString("text") ?: return@collect
                        if (t.isNotEmpty()) {
                            thinkingText.value = (thinkingText.value + t).takeLast(8000)
                            thinking.value = true
                            thinkingHasContent.value = true
                            markChatStateMutation()
                        }
                    }
                    "thinking.delta" -> {
                        // status procesu ("Analizuję plik...", "Czekam na API...") — pelny tekst, nie kumulowany
                        val t = p.optJSONObject("payload")?.optString("text") ?: return@collect
                        if (t.isNotEmpty() && !thinkingHasContent.value) {
                            statusText.value = t
                        }
                        thinking.value = true
                        markChatStateMutation()
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
        markChatStateMutation()
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
        markChatStateMutation()
    }

    fun openChat(bot: BotInfo) {
        activeBot.value = bot
        messages.value = emptyList()
        sessions.value = emptyList()
        val generation = beginSessionTransition()
        store.lastBotName = bot.name
        viewModelScope.launch {
            try {
                val list = client.listSessions(bot.name)
                    .sortedByDescending { it.startedAt }
                if (!shouldApplySessionUpdate(generation, sessionGeneration)) return@launch
                if (list.isEmpty()) {
                    val created = client.createSession(bot.name)
                    activateSession(generation, created.handle, created.model, created.provider)
                } else {
                    sessions.value = list
                }
            } catch (e: Exception) {
                if (shouldApplySessionUpdate(generation, sessionGeneration)) {
                    messages.value = listOf(ChatMessage(ChatMessage.nextId(), false, "⚠️ ${e.message}"))
                }
            }
        }
    }

    fun startNewSession() {
        val bot = activeBot.value ?: return
        sessions.value = emptyList()
        messages.value = emptyList()
        val generation = beginSessionTransition()
        store.lastSessionId = ""
        viewModelScope.launch {
            try {
                val created = client.createSession(bot.name)
                activateSession(generation, created.handle, created.model, created.provider)
            } catch (e: Exception) {
                if (shouldApplySessionUpdate(generation, sessionGeneration)) {
                    messages.value = listOf(ChatMessage(ChatMessage.nextId(), false, "⚠️ ${e.message}"))
                }
            }
        }
    }

    fun resumeChat(info: SessionInfo) {
        val bot = activeBot.value ?: return
        sessions.value = emptyList()
        val generation = beginSessionTransition()
        thinking.value = true
        viewModelScope.launch {
            try {
                val resumed = client.resumeSession(bot.name, info.id)
                if (!activateSession(
                        generation = generation,
                        handle = resumed.handle,
                        model = resumed.model,
                        provider = resumed.provider,
                        requestedStoredSessionId = info.id
                    )) return@launch
                messages.value = resumed.messages.map { (fromUser, text) ->
                    ChatMessage(ChatMessage.nextId(), fromUser, text)
                }
            } catch (e: Exception) {
                if (shouldApplySessionUpdate(generation, sessionGeneration)) {
                    messages.value = listOf(ChatMessage(ChatMessage.nextId(), false, "⚠️ ${e.message}"))
                }
            } finally {
                if (shouldApplySessionUpdate(generation, sessionGeneration)) thinking.value = false
            }
        }
    }

    /** Opens the exact durable chat named by an FCM target or an Outbox Center item. */
    fun openNotificationTarget(target: NotificationTarget): Boolean =
        openStoredSession(target.profileName, target.storedSessionId)

    fun openOutboxEntry(entryId: String): Boolean {
        val entry = outbox.entryById(entryId) ?: return false
        val profile = entry.profileName ?: return false
        return openStoredSession(profile, entry.storedSessionId)
    }

    private fun openStoredSession(profileName: String, requestedStoredSessionId: String): Boolean {
        if (!connected.value || profileName.isBlank() || requestedStoredSessionId.isBlank()) return false
        val bot = bots.value.firstOrNull { it.name == profileName } ?: return false
        activeBot.value = bot
        sessions.value = emptyList()
        messages.value = emptyList()
        val generation = beginSessionTransition()
        if (::store.isInitialized) store.lastBotName = bot.name
        thinking.value = true
        viewModelScope.launch {
            var resumedRunning = false
            try {
                val resumed = client.resumeSession(bot.name, requestedStoredSessionId)
                resumedRunning = resumed.isRunning
                if (!activateSession(
                        generation = generation,
                        handle = resumed.handle,
                        model = resumed.model,
                        provider = resumed.provider,
                        requestedStoredSessionId = requestedStoredSessionId
                    )) return@launch
                messages.value = resumed.messages.map { (fromUser, text) ->
                    ChatMessage(ChatMessage.nextId(), fromUser, text)
                }
            } catch (e: Exception) {
                if (shouldApplySessionUpdate(generation, sessionGeneration)) {
                    messages.value = listOf(ChatMessage(ChatMessage.nextId(), false, "⚠️ ${e.message}"))
                }
            } finally {
                if (shouldApplySessionUpdate(generation, sessionGeneration)) {
                    thinking.value = resumedRunning
                }
            }
        }
        return true
    }

    fun send(text: String) {
        val sid = sessionId ?: return
        val durableSessionId = storedSessionId ?: return
        if (thinking.value || messages.value.lastOrNull()?.streaming == true ||
            modelSwitchInFlight.value || awaitingDeferredTurnBoundary.value ||
            awaitingDeferredModelResolution.value
        ) return
        val generation = sessionGeneration
        val profileName = activeBot.value?.name ?: return
        val entry = outbox.enqueue(durableSessionId, text, profileName)
        persistOutbox()
        markChatStateMutation()
        thinking.value = true
        // nowa tura: czysc podglad myslenia
        thinkingText.value = ""
        statusText.value = ""
        thinkingHasContent.value = false
        thinkingOpen.value = true
        viewModelScope.launch {
            outboxFlushMutex.withLock {
                if (!canSubmitOutboxEntry(outbox.entryById(entry.id), entry)) return@withLock
                if (deferBehindEarlierOutboxEntry(entry)) return@withLock
                when (val result = client.submitPromptResult(sid, entry.text)) {
                    is PromptSubmissionResult.Accepted -> {
                        outbox.acknowledge(entry)
                        persistOutbox()
                        if (isCurrentPromptScope(sid, durableSessionId, generation)) {
                            messages.value += ChatMessage(ChatMessage.nextId(), fromUser = true, text = entry.text)
                            notePromptSubmitted()
                            thinking.value = true
                            markChatStateMutation()
                        }
                    }
                    PromptSubmissionResult.NotSent -> {
                        if (isCurrentPromptScope(sid, durableSessionId, generation)) {
                            thinking.value = false
                            appendPromptDeliveryNotice("Wiadomość oczekuje na ponowne połączenie.")
                        }
                        offline.value = true
                        quietReconnectLoop()
                    }
                    is PromptSubmissionResult.Rejected -> {
                        outbox.reject(entry, result.message)
                        persistOutbox()
                        if (isCurrentPromptScope(sid, durableSessionId, generation)) {
                            thinking.value = false
                            appendPromptDeliveryNotice(result.message)
                        }
                    }
                    PromptSubmissionResult.Indeterminate -> {
                        outbox.markIndeterminate(entry)
                        persistOutbox()
                        if (isCurrentPromptScope(sid, durableSessionId, generation)) {
                            thinking.value = false
                            appendPromptDeliveryNotice(
                                "Nie można potwierdzić wysłania. Nie wyślę go ponownie automatycznie, aby uniknąć duplikatu."
                            )
                        }
                        offline.value = true
                        quietReconnectLoop()
                    }
                }
            }
        }
    }

    fun closeChat() {
        activeBot.value = null
        messages.value = emptyList()
        sessions.value = emptyList()
        _viewSessionHistory.value = false
        sessionHistoryEntries.value = emptyList()
        sessionHistoryRequestEpoch += 1
        beginSessionTransition()
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
    private val _viewOutbox = MutableStateFlow(false)
    val viewOutbox = _viewOutbox.asStateFlow()
    private val _viewHealth = MutableStateFlow(false)
    val viewHealth = _viewHealth.asStateFlow()
    private val _viewSessionHistory = MutableStateFlow(false)
    val viewSessionHistory = _viewSessionHistory.asStateFlow()
    val sessionHistoryEntries = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessionHistoryArchived = MutableStateFlow(false)
    val sessionHistoryLoading = MutableStateFlow(false)
    val sessionHistoryError = MutableStateFlow<String?>(null)
    private var sessionHistoryRequestEpoch = 0

    fun openOutbox() {
        _viewHealth.value = false
        _viewSessionHistory.value = false
        _viewOutbox.value = true
    }

    fun closeOutbox() { _viewOutbox.value = false }

    fun openHealth() {
        _viewOutbox.value = false
        _viewSessionHistory.value = false
        _viewHealth.value = true
    }

    fun closeHealth() { _viewHealth.value = false }

    /** Opens metadata-only conversation history without changing the active chat identity. */
    fun openSessionHistory(archived: Boolean = false) {
        val bot = activeBot.value ?: return
        _viewOutbox.value = false
        _viewHealth.value = false
        _viewRoutines.value = false
        _viewSessionHistory.value = true
        sessionHistoryError.value = null
        sessionHistoryArchived.value = archived
        refreshSessionHistory(bot, archived)
    }

    fun closeSessionHistory() {
        _viewSessionHistory.value = false
        sessionHistoryRequestEpoch += 1
        sessionHistoryLoading.value = false
    }

    fun showArchivedSessionHistory() {
        val bot = activeBot.value ?: return
        if (!_viewSessionHistory.value) return
        sessionHistoryArchived.value = true
        refreshSessionHistory(bot, archived = true)
    }

    fun showActiveSessionHistory() {
        val bot = activeBot.value ?: return
        if (!_viewSessionHistory.value) return
        sessionHistoryArchived.value = false
        refreshSessionHistory(bot, archived = false)
    }

    fun archiveSessionHistoryEntry(entry: SessionInfo) {
        val bot = activeBot.value ?: return
        if (!_viewSessionHistory.value || sessionHistoryArchived.value || entry.isActive || entry.archived) return
        if (sessionHistoryEntries.value.none { it.id == entry.id && !it.isActive && !it.archived }) return
        sessionHistoryError.value = null
        viewModelScope.launch {
            try {
                client.updateStoredSession(profile = bot.name, storedSessionId = entry.id, archived = true)
            } catch (_: Exception) {
                if (_viewSessionHistory.value && activeBot.value?.name == bot.name && !sessionHistoryArchived.value) {
                    sessionHistoryError.value = "Nie udało się zarchiwizować rozmowy."
                }
                return@launch
            }
            refreshSessionHistory(bot, archived = false)
        }
    }

    fun renameSessionHistoryEntry(entry: SessionInfo, rawTitle: String) {
        val bot = activeBot.value ?: return
        val title = rawTitle.trim()
        if (title.isEmpty() || !_viewSessionHistory.value || entry.isActive) return
        if (sessionHistoryEntries.value.none { it.id == entry.id && !it.isActive }) return
        val archived = sessionHistoryArchived.value
        viewModelScope.launch {
            try {
                client.updateStoredSession(profile = bot.name, storedSessionId = entry.id, title = title)
            } catch (_: Exception) {
                return@launch
            }
            refreshSessionHistory(bot, archived)
        }
    }

    fun restoreSessionHistoryEntry(entry: SessionInfo) {
        val bot = activeBot.value ?: return
        if (!_viewSessionHistory.value || !sessionHistoryArchived.value || entry.isActive || !entry.archived) return
        if (sessionHistoryEntries.value.none { it.id == entry.id && it.archived && !it.isActive }) return
        viewModelScope.launch {
            try {
                client.updateStoredSession(profile = bot.name, storedSessionId = entry.id, archived = false)
            } catch (_: Exception) {
                return@launch
            }
            refreshSessionHistory(bot, archived = true)
        }
    }

    fun deleteSessionHistoryEntry(entry: SessionInfo) {
        val bot = activeBot.value ?: return
        if (!_viewSessionHistory.value || entry.isActive) return
        val archived = sessionHistoryArchived.value
        if (sessionHistoryEntries.value.none { it.id == entry.id && !it.isActive && it.archived == archived }) return
        viewModelScope.launch {
            try {
                client.deleteStoredSession(profile = bot.name, storedSessionId = entry.id)
            } catch (_: Exception) {
                return@launch
            }
            refreshSessionHistory(bot, archived)
        }
    }

    private fun refreshSessionHistory(bot: BotInfo, archived: Boolean) {
        val requestEpoch = ++sessionHistoryRequestEpoch
        sessionHistoryLoading.value = true
        viewModelScope.launch {
            val entries = try {
                client.listStoredSessions(bot.name, archived)
            } catch (_: Exception) {
                emptyList()
            }
            if (requestEpoch == sessionHistoryRequestEpoch &&
                _viewSessionHistory.value &&
                activeBot.value?.name == bot.name &&
                sessionHistoryArchived.value == archived
            ) {
                sessionHistoryEntries.value = entries
                sessionHistoryLoading.value = false
            }
        }
    }

    fun openRoutines() {
        val bot = activeBot.value ?: return
        _viewSessionHistory.value = false
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
        _viewSessionHistory.value = false
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
