package eu.draconest.hermesbots.ui

import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.draconest.hermesbots.data.BotInfo
import com.composables.icons.lucide.R
import eu.draconest.hermesbots.data.RoutineInfo

private fun scheduleLabel(schedule: String): String {
    val bare = Regex("""^(\d+)([mhd])$""").find(schedule)
    if (bare != null) return "Raz (${bare.groupValues[1]}${bare.groupValues[2]})"
    val every = Regex("""^every (\d+)m$""").find(schedule)
    if (every != null) {
        val m = every.groupValues[1].toInt()
        if (m % 1440 == 0) { val d = m / 1440; return if (d == 1) "Codziennie" else "Co $d dni" }
        if (m % 60 == 0) { val h = m / 60; return if (h == 1) "Co godzinę" else "Co ${h}h" }
        return "Co $m min"
    }
    val onceIn = Regex("""^once in (.+)$""").find(schedule)
    if (onceIn != null) return "Raz (${onceIn.groupValues[1]})"
    return schedule // cron string itp.
}

/** Routines bota — lista cyklicznych zadan z przełącznikami. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    bot: BotInfo,
    routines: List<RoutineInfo>,
    onToggle: (RoutineInfo, Boolean) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BotAvatar(bot.name, 36.dp)
                        Spacer(Modifier.padding(start = 10.dp))
                        Column {
                            Text(bot.name, style = MaterialTheme.typography.titleMedium)
                            Text("Routines", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.lucide_ic_arrow_left), contentDescription = "Wróć")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        if (routines.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Brak routines", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Cykliczne zadania tego bota pojawią się tutaj.\nTwórz je na desktopie w Bot Mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(routines, key = { it.jobId }) { r ->
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.title, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    scheduleLabel(r.schedule),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (!r.active) {
                                    Text(
                                        "wstrzymana",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(checked = r.active, onCheckedChange = { onToggle(r, it) })
                        }
                    }
                }
            }
        }
    }
}
