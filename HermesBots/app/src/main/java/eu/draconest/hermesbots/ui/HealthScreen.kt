package eu.draconest.hermesbots.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.draconest.hermesbots.data.LocalConnectionHealth
import eu.draconest.hermesbots.data.LocalHealthSnapshot

internal data class HealthPresentation(
    val connectionLabel: String,
    val pendingLabel: String,
    val rejectedLabel: String,
    val indeterminateLabel: String,
    val legacyLabel: String,
    val oldestAgeLabel: String,
    val unknownAgeLabel: String
)

internal fun healthPresentation(snapshot: LocalHealthSnapshot): HealthPresentation = HealthPresentation(
    connectionLabel = when (snapshot.connection) {
        LocalConnectionHealth.Connected -> "Połączono"
        LocalConnectionHealth.Connecting -> "Łączenie…"
        LocalConnectionHealth.NeedsAttention -> "Wymaga uwagi"
        LocalConnectionHealth.Disconnected -> "Rozłączono"
    },
    pendingLabel = snapshot.pendingCount.toString(),
    rejectedLabel = snapshot.rejectedCount.toString(),
    indeterminateLabel = snapshot.indeterminateCount.toString(),
    legacyLabel = snapshot.legacyProfileCount.toString(),
    oldestAgeLabel = formatHealthAge(snapshot.oldestKnownAgeMillis),
    unknownAgeLabel = snapshot.unknownAgeCount.toString()
)

private fun formatHealthAge(ageMillis: Long?): String {
    if (ageMillis == null) return "Brak wpisów"
    val minutes = ageMillis / 60_000L
    if (minutes < 1L) return "Mniej niż minutę"
    if (minutes < 60L) return "$minutes min"
    return "${minutes / 60L} h ${minutes % 60L} min"
}

/** Local-only aggregates: no message content, FCM token, endpoint or raw gateway error is rendered. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthScreen(
    snapshot: LocalHealthSnapshot,
    onBack: () -> Unit
) {
    val presentation = healthPresentation(snapshot)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stan aplikacji") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Wróć") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HealthMetricCard("Połączenie", presentation.connectionLabel)
            HealthMetricCard("Kolejka oczekująca", presentation.pendingLabel)
            HealthMetricCard("Odrzucone", presentation.rejectedLabel)
            HealthMetricCard("Niepewne", presentation.indeterminateLabel)
            HealthMetricCard("Najstarszy znany wpis", presentation.oldestAgeLabel)
            if (snapshot.unknownAgeCount > 0 || snapshot.legacyProfileCount > 0) {
                HealthMetricCard(
                    "Wpisy wymagające ręcznej kontroli",
                    "bez czasu: ${presentation.unknownAgeLabel} · legacy: ${presentation.legacyLabel}"
                )
            }
            Text(
                "Diagnostyka pokazuje tylko lokalne liczniki. Nie zawiera treści wiadomości, tokenów, haseł ani adresów serwera.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun HealthMetricCard(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
