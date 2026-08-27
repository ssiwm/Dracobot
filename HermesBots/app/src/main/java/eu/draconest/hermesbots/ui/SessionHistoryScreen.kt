package eu.draconest.hermesbots.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.draconest.hermesbots.data.BotInfo
import eu.draconest.hermesbots.data.SessionInfo

/**
 * Profile-bound, metadata-only conversation lifecycle view.
 * No preview/message content, routing IDs, URLs, tokens, or raw gateway errors are rendered here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(
    bot: BotInfo,
    sessions: List<SessionInfo>,
    archived: Boolean,
    loading: Boolean,
    error: String?,
    onShowActive: () -> Unit,
    onShowArchived: () -> Unit,
    onResume: (SessionInfo) -> Unit,
    onRename: (SessionInfo, String) -> Unit,
    onArchive: (SessionInfo) -> Unit,
    onRestore: (SessionInfo) -> Unit,
    onDelete: (SessionInfo) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var renameCandidate by remember { mutableStateOf<SessionInfo?>(null) }
    var replacementTitle by remember { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<SessionInfo?>(null) }
    val visibleSessions = filterSessionHistoryByTitle(sessions, searchQuery)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historia · ${bot.name}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wróć")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShowActive, enabled = archived) { Text("Aktywne") }
                TextButton(onClick = onShowArchived, enabled = !archived) { Text("Archiwum") }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Szukaj po tytule") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            when {
                loading -> Text(
                    text = "Odświeżanie…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
                visibleSessions.isEmpty() -> Text(
                    text = if (archived) "Archiwum jest puste." else "Brak aktywnych rozmów.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 12.dp)
                ) {
                    items(visibleSessions, key = { it.id }) { session ->
                        SessionHistoryCard(
                            session = session,
                            onResume = onResume,
                            onRename = {
                                replacementTitle = session.title
                                renameCandidate = session
                            },
                            onArchive = onArchive,
                            onRestore = onRestore,
                            onDelete = { deleteCandidate = session }
                        )
                    }
                }
            }
        }
    }

    renameCandidate?.let { session ->
        AlertDialog(
            onDismissRequest = { renameCandidate = null },
            title = { Text("Zmień tytuł") },
            text = {
                OutlinedTextField(
                    value = replacementTitle,
                    onValueChange = { replacementTitle = it },
                    label = { Text("Tytuł rozmowy") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = replacementTitle.isNotBlank(),
                    onClick = {
                        onRename(session, replacementTitle)
                        renameCandidate = null
                    }
                ) { Text("Zapisz") }
            },
            dismissButton = {
                TextButton(onClick = { renameCandidate = null }) { Text("Anuluj") }
            }
        )
    }

    deleteCandidate?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Usunąć rozmowę?") },
            text = { Text("Ta operacja jest trwała. Nie można jej cofnąć.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(session)
                        deleteCandidate = null
                    }
                ) { Text("Usuń") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Anuluj") }
            }
        )
    }
}

@Composable
private fun SessionHistoryCard(
    session: SessionInfo,
    onResume: (SessionInfo) -> Unit,
    onRename: () -> Unit,
    onArchive: (SessionInfo) -> Unit,
    onRestore: (SessionInfo) -> Unit,
    onDelete: () -> Unit
) {
    val actions = sessionHistoryActionAvailability(session)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = session.title.ifBlank { "Bez tytułu" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${session.messageCount} wiadomości",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (session.isActive) {
                Text(
                    text = "Bieżąca rozmowa — zarządzaj z czatu",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (actions.canResume) TextButton(onClick = { onResume(session) }) { Text("Otwórz") }
                    if (actions.canRename) TextButton(onClick = onRename) { Text("Zmień") }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (actions.canArchive) TextButton(onClick = { onArchive(session) }) { Text("Archiwizuj") }
                    if (actions.canRestore) TextButton(onClick = { onRestore(session) }) { Text("Przywróć") }
                    if (actions.canDelete) TextButton(onClick = onDelete) { Text("Usuń") }
                }
            }
        }
    }
}
