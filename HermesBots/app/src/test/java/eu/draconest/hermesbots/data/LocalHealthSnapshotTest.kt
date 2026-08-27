package eu.draconest.hermesbots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalHealthSnapshotTest {
    @Test
    fun snapshotAggregatesOutboxStatesAndUsesOnlyValidEnqueueTimes() {
        val snapshot = localHealthSnapshot(
            connected = false,
            connecting = false,
            hasConnectionError = true,
            entries = listOf(
                QueuedPrompt(
                    id = "pending",
                    storedSessionId = "stored-a",
                    profileName = "architect",
                    text = "private pending prompt",
                    deliveryState = QueuedPromptDeliveryState.Pending,
                    createdAtEpochMillis = 9_000L
                ),
                QueuedPrompt(
                    id = "indeterminate",
                    storedSessionId = "stored-b",
                    profileName = "designer",
                    text = "private ambiguous prompt",
                    deliveryState = QueuedPromptDeliveryState.Indeterminate,
                    createdAtEpochMillis = 1_000L
                ),
                QueuedPrompt(
                    id = "legacy",
                    storedSessionId = "stored-c",
                    text = "private legacy prompt",
                    deliveryState = QueuedPromptDeliveryState.Rejected,
                    createdAtEpochMillis = 0L
                )
            ),
            nowEpochMillis = 10_000L
        )

        assertEquals(LocalConnectionHealth.NeedsAttention, snapshot.connection)
        assertEquals(1, snapshot.pendingCount)
        assertEquals(1, snapshot.rejectedCount)
        assertEquals(1, snapshot.indeterminateCount)
        assertEquals(1, snapshot.legacyProfileCount)
        assertEquals(1, snapshot.unknownAgeCount)
        assertEquals(9_000L, snapshot.oldestKnownAgeMillis)
        assertFalse(snapshot.toString().contains("private"))
    }

    @Test
    fun futureEnqueueTimeIsClassifiedAsUnknownInsteadOfNegativeAge() {
        val snapshot = localHealthSnapshot(
            connected = true,
            connecting = false,
            hasConnectionError = false,
            entries = listOf(
                QueuedPrompt(
                    id = "future",
                    storedSessionId = "stored-a",
                    profileName = "architect",
                    text = "private future prompt",
                    createdAtEpochMillis = 10_001L
                )
            ),
            nowEpochMillis = 10_000L
        )

        assertEquals(LocalConnectionHealth.Connected, snapshot.connection)
        assertEquals(1, snapshot.unknownAgeCount)
        assertEquals(null, snapshot.oldestKnownAgeMillis)
    }

    @Test
    fun connectionStateNeverExposesRawConnectionErrorText() {
        val snapshot = localHealthSnapshot(
            connected = false,
            connecting = false,
            hasConnectionError = true,
            entries = emptyList(),
            nowEpochMillis = 1L
        )

        assertEquals(LocalConnectionHealth.NeedsAttention, snapshot.connection)
        assertFalse(snapshot.toString().contains("error"))
    }
}
