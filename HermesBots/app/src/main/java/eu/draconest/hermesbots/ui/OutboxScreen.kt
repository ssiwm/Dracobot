package eu.draconest.hermesbots.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.draconest.hermesbots.data.QueuedPrompt
import eu.draconest.hermesbots.data.QueuedPromptDeliveryState

internal data class OutboxActionAvailability(
    val stateLabel: String,
    val deliveryDetail: String?,
    val canOpenConversation: Boolean,
    val canResendAsNew: Boolean
)

internal fun outboxActionAvailability(entry: QueuedPrompt): OutboxActionAvailability =
    OutboxActionAvailability(
        stateLabel = when (entry.deliveryState) {
            QueuedPromptDeliveryState.Pending -> "Oczekuje na dostarczenie"
            QueuedPromptDeliveryState.Rejected -> "Odrzucono przez gateway"
            QueuedPromptDeliveryState.Indeterminate -> "Niepewny rezultat — sprawdź historię"
        },
        deliveryDetail = entry.deliveryDetail,
        canOpenConversation = entry.profileName != null,
        canResendAsNew = entry.profileName != null &&
            entry.deliveryState != QueuedPromptDeliveryState.Pending
    )

private data class OutboxActionCandidate(
    val entry: QueuedPrompt,
    val resendAsNew: Boolean
)

/**
 * User-facing resolution surface for durable outbox entries. It never retries an ambiguous
 * frame implicitly: the only resend path is a confirmation that creates a fresh entry identity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutboxScreen(
    entries: List<QueuedPrompt>,
    onOpenConversation: (QueuedPrompt) -> Unit,
    onResendAsNew: (QueuedPrompt) -> Unit,
    onDiscard: (QueuedPrompt) -> Unit,
    onBack: () -> Unit
) {
    var actionCandidate by remember { mutableStateOf<OutboxActionCandidate?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kolejka wiadomości") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Wróć") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Kolejka jest pusta", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Wiadomości oczekujące na bezpieczne rozwiązanie pojawią się tutaj.",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "Automatyczne ponowienie nie jest wykonywane dla niepotwierdzonych wiadomości.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(entries, key = { it.id }) { entry ->
                    val availability = outboxActionAvailability(entry)
                    val statusColor = when (entry.deliveryState) {
                        QueuedPromptDeliveryState.Pending -> MaterialTheme.colorScheme.primary
                        QueuedPromptDeliveryState.Rejected -> MaterialTheme.colorScheme.error
                        QueuedPromptDeliveryState.Indeterminate -> MaterialTheme.colorScheme.tertiary
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(availability.stateLabel, color = statusColor, style = MaterialTheme.typography.labelLarge)
                            Text(
                                entry.profileName ?: "Starszy wpis — profil nieznany",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            availability.deliveryDetail?.let { detail ->
                                Text(
                                    "Powód: $detail",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Text(
                                entry.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    enabled = availability.canOpenConversation,
                                    onClick = { onOpenConversation(entry) }
                                ) { Text("Otwórz") }
                                if (availability.canResendAsNew) {
                                    Spacer(Modifier.width(4.dp))
                                    TextButton(onClick = {
                                        actionCandidate = OutboxActionCandidate(entry, resendAsNew = true)
                                    }) { Text("Wyślij jako nową") }
                                }
                                Spacer(Modifier.width(4.dp))
                                TextButton(onClick = {
                                    actionCandidate = OutboxActionCandidate(entry, resendAsNew = false)
                                }) { Text("Usuń") }
                            }
                        }
                    }
                }
            }
        }
    }

    actionCandidate?.let { candidate ->
        val title = if (candidate.resendAsNew) "Wysłać jako nową wiadomość?" else "Usunąć z kolejki?"
        val body = if (candidate.resendAsNew) {
            if (candidate.entry.deliveryState == QueuedPromptDeliveryState.Indeterminate) {
                "Gateway mógł już otrzymać poprzednią próbę. Kontynuacja utworzy nową wiadomość i może spowodować duplikat."
            } else {
                "Poprzednia próba została odrzucona. Kontynuacja utworzy świeżą wiadomość z nową tożsamością kolejki."
            }
        } else {
            "Ta wiadomość nie zostanie ponowiona automatycznie."
        }
        AlertDialog(
            onDismissRequest = { actionCandidate = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    if (candidate.resendAsNew) onResendAsNew(candidate.entry) else onDiscard(candidate.entry)
                    actionCandidate = null
                }) { Text(if (candidate.resendAsNew) "Wyślij" else "Usuń") }
            },
            dismissButton = {
                TextButton(onClick = { actionCandidate = null }) { Text("Anuluj") }
            }
        )
    }
}
