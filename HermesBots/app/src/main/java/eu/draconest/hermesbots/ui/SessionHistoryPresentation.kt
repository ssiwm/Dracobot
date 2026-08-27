package eu.draconest.hermesbots.ui

import eu.draconest.hermesbots.data.SessionInfo

/**
 * Local metadata filter for the conversation picker.
 *
 * It deliberately searches only session titles: previews can contain message
 * content and must not turn a metadata search into transcript search.
 */
internal fun filterSessionHistoryByTitle(
    sessions: List<SessionInfo>,
    rawQuery: String
): List<SessionInfo> {
    val query = rawQuery.trim()
    return if (query.isEmpty()) sessions else sessions.filter { session ->
        session.title.contains(query, ignoreCase = true)
    }
}

data class SessionHistoryActionAvailability(
    val canResume: Boolean,
    val canRename: Boolean,
    val canArchive: Boolean,
    val canRestore: Boolean,
    val canDelete: Boolean
)

internal fun sessionHistoryActionAvailability(session: SessionInfo): SessionHistoryActionAvailability =
    if (session.isActive) {
        SessionHistoryActionAvailability(
            canResume = true,
            canRename = false,
            canArchive = false,
            canRestore = false,
            canDelete = false
        )
    } else {
        SessionHistoryActionAvailability(
            canResume = !session.archived,
            canRename = true,
            canArchive = !session.archived,
            canRestore = session.archived,
            canDelete = true
        )
    }
