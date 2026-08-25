package eu.draconest.hermesbots.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.draconest.hermesbots.data.BotInfo
import eu.draconest.hermesbots.data.ChatMessage

/** Determinystyczny „blob avatar" z nazwy bota — patrz BotAvatar.kt (1:1 z desktopu). */

/** Roster botów — home ekran w stylu Grok. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterScreen(
    bots: List<BotInfo>,
    groups: List<String>,
    onOpen: (BotInfo) -> Unit,
    onOpenGroup: (String) -> Unit,
    onNewGroup: () -> Unit,
    onDeleteGroup: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
    onCreateBot: (String) -> Unit = {},
    onDeleteBot: (String) -> Unit = {},
    creatingBot: Boolean = false
) {
    val refreshing = remember { androidx.compose.runtime.mutableStateOf(false) }
    val refreshScope = androidx.compose.runtime.rememberCoroutineScope()
    var showCreateBot by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.runtime.LaunchedEffect(refreshing.value) {
        if (refreshing.value) {
            kotlinx.coroutines.delay(600)
            refreshing.value = false
        }
    }
    Scaffold(
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { showCreateBot = true },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Text(if (creatingBot) "Tworzę…" else "+ Bot")
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // dialog tworzenia bota
            if (showCreateBot) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showCreateBot = false },
                    title = { Text("Nowy bot") },
                    text = {
                        Column {
                            Text("Nazwa profilu: małe litery, cyfry, - _ (max 64).",
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                singleLine = true,
                                label = { Text("np. research-bot") }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("Bot dostanie kopię umiejętności i dostęp do modelu.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            onCreateBot(newName.trim())
                            newName = ""
                            showCreateBot = false
                        }, enabled = newName.isNotBlank()) { Text("Utwórz") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showCreateBot = false }) {
                            Text("Anuluj")
                        }
                    }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp)
            ) {
                Text("Boty", style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onNewGroup) {
                    Icon(Icons.Filled.DateRange, "Nowa grupa botów")
                }
            }
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = refreshing.value,
                onRefresh = {
                    refreshing.value = true
                    onRefresh()
                }
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                // --- sekcja grup ---
                if (groups.isNotEmpty()) {
                    item {
                        Text("Grupy", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
                    }
                    items(groups, key = { "grp_$it" }) { g ->
                        Card(
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 5.dp)
                                .clickable { onOpenGroup(g) }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(16.dp)) {
                                Text("👥 ${g}", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f))
                                Text(
                                    "otwórz",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            "Grupy: utwórz ikoną 📅 powyżej",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
                        )
                    }
                }
                // --- sekcja boty ---
                item {
                    Text("Boty", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
                }
                items(bots, key = { it.name }) { bot ->
                    Card(
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .combinedClickableCompat(onClick = { onOpen(bot) }, onLongClick = {
                                if (bot.name != "default") {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    onDeleteBot(bot.name)
                                }
                            })
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            BotAvatar(bot.name, 44.dp)
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(bot.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${bot.model ?: "?"} · ${bot.skillCount} skills",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (bot.gatewayRunning) {
                                Box(
                                    Modifier.size(10.dp).background(
                                        MaterialTheme.colorScheme.primary, CircleShape
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/** Czat 1:1 z botem — dymki + streaming + composer jak w Grok. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    bot: BotInfo,
    messages: List<ChatMessage>,
    thinking: Boolean,
    offline: Boolean = false,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
    onRoutines: () -> Unit = {}
) {
    var input by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // auto-scroll do najnowszej wiadomosci
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(messages.size, thinking) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BotAvatar(bot.name, 36.dp)
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(bot.name, style = MaterialTheme.typography.titleMedium)
                            if (thinking) Text(
                                "myśli…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wróć")
                    }
                },
                actions = {
                    IconButton(onClick = onRoutines) {
                        Icon(Icons.Filled.DateRange, "Routines")
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
                placeholder = { Text("Napisz do ${bot.name}…") },
                shape = RoundedCornerShape(28.dp),
                maxLines = 5,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                onSend(input.trim())
                                input = ""
                                keyboard?.hide()
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            }
                        },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Wyślij")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (offline) {
                Text(
                    "⚫ brak połączenia — wiadomości wyślą się po powrocie sieci",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages.asReversed(), key = { it.id }) { msg ->
                    Bubble(
                        msg,
                        onLongPress = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.text))
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Bubble(msg: ChatMessage, onLongPress: () -> Unit = {}) {
    val bg = animateColorAsState(
        if (msg.fromUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "bubble"
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.85f)
                .combinedClickableCompat(onLongPress)
                .background(bg.value, RoundedCornerShape(24.dp))
                .padding(14.dp)
        ) {
            Text(msg.text + if (msg.streaming) "▍" else "")
        }
    }
}

/** combinedClickable wymaga ExperimentalFoundationApi — opakowanie. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit = {}, onLongClick: () -> Unit = {}): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** Dialog potwierdzenia usunięcia bota. */
@Composable
fun DeleteBotDialog(botName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Usunąć bota?") },
        text = {
            Text("Bot \"$botName\" zostanie trwale usunięty razem z pamięcią, historią rozmów i plikami profilu. Tej operacji nie można cofnąć.")
        },
        confirmButton = {
            Button(onClick = { onConfirm(); onDismiss() }) {
                Text("Usuń", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Anuluj") }
        }
    )
}
