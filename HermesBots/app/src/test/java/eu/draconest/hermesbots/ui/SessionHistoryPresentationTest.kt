package eu.draconest.hermesbots.ui

import eu.draconest.hermesbots.data.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryPresentationTest {
    private val titleMatch = SessionInfo(
        id = "stored-title-match",
        title = "Projekt Apollo",
        preview = "nie powinien mieć znaczenia",
        messageCount = 3,
        startedAt = 1L
    )
    private val previewOnlyMatch = SessionInfo(
        id = "stored-preview-match",
        title = "Ogólny",
        preview = "Apollo występuje tylko w podglądzie",
        messageCount = 2,
        startedAt = 2L
    )

    @Test
    fun titleSearchMatchesTitleWithoutSearchingMessagePreview() {
        assertEquals(
            listOf(titleMatch),
            filterSessionHistoryByTitle(listOf(titleMatch, previewOnlyMatch), "apollo")
        )
    }

    @Test
    fun blankTitleSearchPreservesAllEntriesInTheirExistingOrder() {
        assertEquals(
            listOf(previewOnlyMatch, titleMatch),
            filterSessionHistoryByTitle(listOf(previewOnlyMatch, titleMatch), "  ")
        )
    }
    @Test
    fun activeSessionCannotBeMutatedFromHistoryList() {
        val availability = sessionHistoryActionAvailability(titleMatch.copy(isActive = true))

        assertTrue(availability.canResume)
        assertFalse(availability.canRename)
        assertFalse(availability.canArchive)
        assertFalse(availability.canRestore)
        assertFalse(availability.canDelete)
    }
}
