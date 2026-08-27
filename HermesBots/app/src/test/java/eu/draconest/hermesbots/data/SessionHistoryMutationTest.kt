package eu.draconest.hermesbots.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryMutationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun archiveMutatesOnlyInactiveEntryInTheActiveProfileAndRefreshesList() = runTest(dispatcher) {
        val inactive = SESSION.copy(isActive = false)
        val active = SESSION.copy(id = "stored-active", isActive = true)
        val client = HistoryGateway(activeEntries = mutableListOf(inactive, active))
        val viewModel = AppViewModel(client)
        viewModel.activeBot.value = BOT
        viewModel.openSessionHistory()
        advanceUntilIdle()

        viewModel.archiveSessionHistoryEntry(inactive)
        viewModel.archiveSessionHistoryEntry(active)
        advanceUntilIdle()

        assertEquals(listOf(MutationCall(BOT.name, inactive.id, archived = true)), client.mutations)
        assertEquals(listOf(active), viewModel.sessionHistoryEntries.value)
    }

    @Test
    fun renameTrimsTitleAndRefusesBlankTitle() = runTest(dispatcher) {
        val client = HistoryGateway(activeEntries = mutableListOf(SESSION))
        val viewModel = AppViewModel(client)
        viewModel.activeBot.value = BOT
        viewModel.openSessionHistory()
        advanceUntilIdle()

        viewModel.renameSessionHistoryEntry(SESSION, "  Nowy tytuł  ")
        viewModel.renameSessionHistoryEntry(SESSION, "   ")
        advanceUntilIdle()

        assertEquals(
            listOf(MutationCall(BOT.name, SESSION.id, title = "Nowy tytuł", archived = null)),
            client.mutations
        )
    }

    @Test
    fun restoreMutatesOnlyArchivedEntryAndRefreshesArchivedList() = runTest(dispatcher) {
        val archived = SESSION.copy(archived = true)
        val client = HistoryGateway(
            activeEntries = mutableListOf(),
            archivedEntries = mutableListOf(archived)
        )
        val viewModel = AppViewModel(client)
        viewModel.activeBot.value = BOT
        viewModel.openSessionHistory(archived = true)
        advanceUntilIdle()

        viewModel.restoreSessionHistoryEntry(archived)
        advanceUntilIdle()

        assertEquals(listOf(MutationCall(BOT.name, archived.id, archived = false)), client.mutations)
        assertEquals(emptyList<SessionInfo>(), viewModel.sessionHistoryEntries.value)
    }

    @Test
    fun deleteMutatesOnlyInactiveEntryAndRefreshesList() = runTest(dispatcher) {
        val client = HistoryGateway(activeEntries = mutableListOf(SESSION))
        val viewModel = AppViewModel(client)
        viewModel.activeBot.value = BOT
        viewModel.openSessionHistory()
        advanceUntilIdle()

        viewModel.deleteSessionHistoryEntry(SESSION)
        advanceUntilIdle()

        assertEquals(listOf(DeleteCall(BOT.name, SESSION.id)), client.deletions)
        assertEquals(emptyList<SessionInfo>(), viewModel.sessionHistoryEntries.value)
    }

    @Test
    fun archiveFailurePreservesEntryAndReportsGenericMessage() = runTest(dispatcher) {
        val client = HistoryGateway(activeEntries = mutableListOf(SESSION), failUpdate = true)
        val viewModel = AppViewModel(client)
        viewModel.activeBot.value = BOT
        viewModel.openSessionHistory()
        advanceUntilIdle()

        viewModel.archiveSessionHistoryEntry(SESSION)
        advanceUntilIdle()

        assertEquals(listOf(SESSION), viewModel.sessionHistoryEntries.value)
        assertEquals("Nie udało się zarchiwizować rozmowy.", viewModel.sessionHistoryError.value)
    }

    private data class MutationCall(
        val profile: String,
        val sessionId: String,
        val title: String? = null,
        val archived: Boolean?
    )

    private data class DeleteCall(val profile: String, val sessionId: String)

    private class HistoryGateway(
        activeEntries: MutableList<SessionInfo>,
        private val archivedEntries: MutableList<SessionInfo> = mutableListOf(),
        private val failUpdate: Boolean = false
    ) : GatewayClient() {
        private val activeEntries = activeEntries
        val mutations = mutableListOf<MutationCall>()
        val deletions = mutableListOf<DeleteCall>()

        override suspend fun listStoredSessions(profile: String, archived: Boolean): List<SessionInfo> =
            if (archived) archivedEntries.toList() else activeEntries.toList()

        override suspend fun updateStoredSession(
            profile: String,
            storedSessionId: String,
            title: String?,
            archived: Boolean?
        ) {
            if (failUpdate) error("fixture transport failure")
            mutations += MutationCall(profile, storedSessionId, title, archived)
            when (archived) {
                true -> activeEntries.removeAll { it.id == storedSessionId }
                false -> archivedEntries.removeAll { it.id == storedSessionId }
                null -> Unit
            }
        }

        override suspend fun deleteStoredSession(profile: String, storedSessionId: String) {
            deletions += DeleteCall(profile, storedSessionId)
            activeEntries.removeAll { it.id == storedSessionId }
            archivedEntries.removeAll { it.id == storedSessionId }
        }
    }

    private companion object {
        val BOT = BotInfo("bot-a", model = null, skillCount = 0, gatewayRunning = true)
        val SESSION = SessionInfo(
            id = "stored-inactive",
            title = "Rozmowa",
            preview = "",
            messageCount = 2,
            startedAt = 1L
        )
    }
}
