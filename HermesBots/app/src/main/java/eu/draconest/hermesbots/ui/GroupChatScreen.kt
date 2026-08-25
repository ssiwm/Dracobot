package eu.draconest.hermesbots.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.draconest.hermesbots.data.BotInfo
import eu.draconest.hermesbots.data.GroupChatEngine

/** Czat grupowy: pokoj z logiem, kazdy bot ma swoj avatar i kolor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupName: String,
    members: List<BotInfo>,
    log: List<GroupChatEngine.Entry>,
    running: Boolean,
    offline: Boolean,
    onSend: (String) -> Unit,
    onBack: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val byName = members.associateBy { it.name.lowercase() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // awatary czlonkow w naglowku — animowane gdy grupa deliberuje
                        Row {
                            members.take(3).forEach { m ->
                                BotAvatar(m.name, 28.dp, working = running)
                                Spacer(Modifier.width(2.dp))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(groupName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (running) "boty deliberują…" else members.joinToString(", ") { "@${it.name}" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wróć")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Napisz do grupy…") },
                shape = RoundedCornerShape(28.dp),
                maxLines = 4,
                trailingIcon = {
                    IconButton(
                        onClick = { if (input.isNotBlank()) { onSend(input.trim()); input = "" } },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Wyślij")
                    }
                },
                modifier = Modifier.fillMaxWidth().imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (offline) {
                Text(
                    "⚫ brak połączenia",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            LazyColumn(
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(log.asReversed(), key = { "${it.at}_${it.fromName}_${it.text.hashCode()}" }) { entry ->
                    if (entry.fromKind == "user") {
                        // user po prawej, jak w czacie 1:1
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Box2 {
                                Text(entry.text, modifier = Modifier
                                    .background(MaterialTheme.colorScheme.primaryContainer,
                                        RoundedCornerShape(24.dp))
                                    .padding(14.dp))
                            }
                        }
                    } else {
                        val bot = byName[entry.fromName.lowercase()]
                        Row(verticalAlignment = Alignment.Top) {
                            BotAvatar(entry.fromName, 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    entry.fromName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                val bg = animateColorAsState(
                                    MaterialTheme.colorScheme.surfaceVariant, label = "g"
                                )
                                Text(
                                    markdownToAnnotated(entry.text),
                                    modifier = Modifier.background(bg.value, RoundedCornerShape(20.dp))
                                        .padding(12.dp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Box2(content: @Composable () -> Unit) { content() }
