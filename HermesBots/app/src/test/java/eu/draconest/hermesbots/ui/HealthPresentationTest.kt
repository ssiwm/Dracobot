package eu.draconest.hermesbots.ui

import eu.draconest.hermesbots.data.LocalConnectionHealth
import eu.draconest.hermesbots.data.LocalHealthSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthPresentationTest {
    @Test
    fun presentationShowsAggregateCountsAndNeverNeedsConnectionErrorText() {
        val presentation = healthPresentation(
            LocalHealthSnapshot(
                connection = LocalConnectionHealth.NeedsAttention,
                pendingCount = 2,
                rejectedCount = 1,
                indeterminateCount = 3,
                legacyProfileCount = 1,
                unknownAgeCount = 1,
                oldestKnownAgeMillis = 3_660_000L
            )
        )

        assertEquals("Wymaga uwagi", presentation.connectionLabel)
        assertEquals("2", presentation.pendingLabel)
        assertEquals("1", presentation.rejectedLabel)
        assertEquals("3", presentation.indeterminateLabel)
        assertEquals("1", presentation.legacyLabel)
        assertEquals("1 h 1 min", presentation.oldestAgeLabel)
        assertEquals("1", presentation.unknownAgeLabel)
    }

    @Test
    fun emptyQueueUsesExplicitNoEntriesLabels() {
        val presentation = healthPresentation(
            LocalHealthSnapshot(
                connection = LocalConnectionHealth.Connected,
                pendingCount = 0,
                rejectedCount = 0,
                indeterminateCount = 0,
                legacyProfileCount = 0,
                unknownAgeCount = 0,
                oldestKnownAgeMillis = null
            )
        )

        assertEquals("Połączono", presentation.connectionLabel)
        assertEquals("Brak wpisów", presentation.oldestAgeLabel)
        assertEquals("0", presentation.unknownAgeLabel)
    }
}
