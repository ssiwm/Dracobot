package eu.draconest.hermesbots.data

/** Connection state intentionally excludes endpoint, credential and raw error details. */
internal enum class LocalConnectionHealth {
    Connected,
    Connecting,
    NeedsAttention,
    Disconnected
}

/** Aggregate-only local diagnostics for the Health screen. It contains no prompt or routing data. */
internal data class LocalHealthSnapshot(
    val connection: LocalConnectionHealth,
    val pendingCount: Int,
    val rejectedCount: Int,
    val indeterminateCount: Int,
    val legacyProfileCount: Int,
    val unknownAgeCount: Int,
    val oldestKnownAgeMillis: Long?
)

internal fun localHealthSnapshot(
    connected: Boolean,
    connecting: Boolean,
    hasConnectionError: Boolean,
    entries: List<QueuedPrompt>,
    nowEpochMillis: Long
): LocalHealthSnapshot {
    require(nowEpochMillis >= 0L) { "Clock must not be negative" }

    var pendingCount = 0
    var rejectedCount = 0
    var indeterminateCount = 0
    var legacyProfileCount = 0
    var unknownAgeCount = 0
    var oldestKnownAgeMillis: Long? = null

    entries.forEach { entry ->
        when (entry.deliveryState) {
            QueuedPromptDeliveryState.Pending -> pendingCount += 1
            QueuedPromptDeliveryState.Rejected -> rejectedCount += 1
            QueuedPromptDeliveryState.Indeterminate -> indeterminateCount += 1
        }
        if (entry.profileName == null) legacyProfileCount += 1

        val createdAt = entry.createdAtEpochMillis
        if (createdAt <= 0L || createdAt > nowEpochMillis) {
            unknownAgeCount += 1
        } else {
            val ageMillis = nowEpochMillis - createdAt
            oldestKnownAgeMillis = maxOf(oldestKnownAgeMillis ?: ageMillis, ageMillis)
        }
    }

    val connection = when {
        connected -> LocalConnectionHealth.Connected
        connecting -> LocalConnectionHealth.Connecting
        hasConnectionError -> LocalConnectionHealth.NeedsAttention
        else -> LocalConnectionHealth.Disconnected
    }

    return LocalHealthSnapshot(
        connection = connection,
        pendingCount = pendingCount,
        rejectedCount = rejectedCount,
        indeterminateCount = indeterminateCount,
        legacyProfileCount = legacyProfileCount,
        unknownAgeCount = unknownAgeCount,
        oldestKnownAgeMillis = oldestKnownAgeMillis
    )
}
