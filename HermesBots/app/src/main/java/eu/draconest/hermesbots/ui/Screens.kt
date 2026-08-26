package eu.draconest.hermesbots.ui

import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.animation.animateColorAsState
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.composables.icons.lucide.R
import eu.draconest.hermesbots.data.ChatMessage

/** Determinystyczny „blob avatar" z nazwy bota — patrz BotAvatar.kt (1:1 z desktopu). */

/** Roster botów — home ekran w stylu Grok. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterScreen(
    bots: List<BotInfo>,
    groups: List<String>,
    summaries: Map<String, eu.draconest.hermesbots.data.RosterSummary> = emptyMap(),
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
                    Icon(painterResource(R.drawable.lucide_ic_users_round), contentDescription = "Nowa grupa botów")
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
                    val summary = summaries[bot.name]
                    // "Aktywne teraz" = aktywnosc < 15 min
                    val nowSec = System.currentTimeMillis() / 1000
                    val isActive = summary != null && (nowSec - summary.newestActiveAt) < 900
                    Card(
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceVariant
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
                            Box {
                                BotAvatar(bot.name, 44.dp)
                                // badge liczby rozmow
                                if (summary != null && summary.chatCount > 0) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 6.dp, y = (-4).dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            "${summary.chatCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(bot.name, style = MaterialTheme.typography.titleMedium)
                                    if (isActive) {
                                        Spacer(Modifier.width(6.dp))
                                        Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                    }
                                }
                                val subtitle = if (summary != null) {
                                    val ageMin = ((nowSec - summary.newestActiveAt) / 60).coerceAtLeast(0)
                                    val when_ = when {
                                        ageMin < 1 -> "teraz"
                                        ageMin < 60 -> "$ageMin min temu"
                                        ageMin < 1440 -> "${ageMin / 60} godz. temu"
                                        else -> "${ageMin / 1440} dni temu"
                                    }
                                    "$when_ · ${summary.newestTitle}"
                                } else {
                                    "${bot.model ?: "?"} · ${bot.skillCount} skills"
                                }
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
    thinkingText: String = "",
    statusText: String = "",
    thinkingOpen: Boolean = true,
    onToggleThinking: () -> Unit = {},
    offline: Boolean = false,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
    onRoutines: () -> Unit = {},
    currentModel: String = "",
    currentProvider: String = "",
    onSwitchModel: suspend (String) -> String? = { null },
    modelOptionsLoader: suspend () -> List<Pair<String, List<String>>> = { emptyList() },
    attachError: String? = null,
    onClearAttachError: () -> Unit = {},
    pickFileLauncher: (() -> Unit)? = null,
    generatingImage: Boolean = false,
    onGenerateImage: suspend (String) -> String? = { null },
    onRegenerate: () -> Unit = {},
    regenerateEnabled: Boolean = true
) {
    var input by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var showModelPicker by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showImageDialog by remember { mutableStateOf(false) }
    var imagePrompt by remember { mutableStateOf("") }

    if (showImageDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showImageDialog = false },
            title = { Text("Generuj obraz") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = imagePrompt,
                        onValueChange = { imagePrompt = it },
                        placeholder = { Text("Opis obrazu, np. „kot astronauta w stylu akwareli”") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        val p = imagePrompt.trim()
                        showImageDialog = false
                        if (p.isNotEmpty()) scope.launch { onGenerateImage(p)?.let { } }
                    },
                    enabled = imagePrompt.isNotBlank() && !generatingImage
                ) { Text(if (generatingImage) "Generuję…" else "Generuj") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showImageDialog = false }) { Text("Anuluj") }
            }
        )
    }

    if (showModelPicker) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showModelPicker = false }) {
            ModelPickerContent(
                currentModel = currentModel,
                currentProvider = currentProvider,
                onPick = { picked ->
                    showModelPicker = false
                    scope.launch {
                        onSwitchModel(picked)?.let { err ->
                            android.widget.Toast.makeText(context, "⚠️ $err", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
                loadOptions = { modelOptionsLoader() }
            )
        }
    }

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
                        BotAvatar(bot.name, 36.dp, working = thinking)
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(bot.name, style = MaterialTheme.typography.titleMedium)
                            // klikalny model — otwiera picker
                            Text(
                                (currentModel.ifBlank { "model" }) + " ▾",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.combinedClickableCompat(onClick = { showModelPicker = true })
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.lucide_ic_arrow_left), contentDescription = "Wróć")
                    }
                },
                actions = {
                    IconButton(onClick = onRoutines) {
                        Icon(painterResource(R.drawable.lucide_ic_calendar), contentDescription = "Routines")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Column {
                // pasek bledu zalacznika
                attachError?.let { err ->
                    Text(
                        "⚠️ $err — dotknij, by ukryć",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickableCompat(onClick = onClearAttachError)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Napisz do ${bot.name}…") },
                    shape = RoundedCornerShape(28.dp),
                    maxLines = 5,
                    leadingIcon = {
                        Row {
                            // generuj obraz (Lucide image)
                            IconButton(onClick = { showImageDialog = true }) {
                                Icon(painterResource(R.drawable.lucide_ic_image), contentDescription = "Generuj obraz",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            // załącz plik (Lucide paperclip)
                            IconButton(onClick = { pickFileLauncher?.invoke() }) {
                                Icon(painterResource(R.drawable.lucide_ic_paperclip), contentDescription = "Załącz plik",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
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
                        Icon(painterResource(R.drawable.lucide_ic_send), contentDescription = "Wyślij")
                    }
                },
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
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
                // pusty czat -> sugestie promptow jak w Groku
                if (messages.isEmpty() && !thinking) {
                    item(key = "suggestions") {
                        SuggestionChips(
                            botName = bot.name,
                            onPick = { suggestion ->
                                onSend(suggestion)
                                keyboard?.hide()
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            }
                        )
                    }
                }
                // panel "myslenia": status procesu + akumulowany reasoning (zwijany)
                if (thinking && (thinkingText.isNotBlank() || statusText.isNotBlank())) {
                    item(key = "thinking-panel") {
                        ThinkingPanel(
                            reasoning = thinkingText,
                            status = statusText,
                            expanded = thinkingOpen,
                            onToggle = onToggleThinking
                        )
                    }
                }
                items(messages.asReversed(), key = { it.id }) { msg ->
                    Bubble(
                        msg,
                        onLongPress = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.text))
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        },
                        onRegenerate = if (!msg.fromUser && !msg.streaming && regenerateEnabled) {
                            { onRegenerate() }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun Bubble(
    msg: ChatMessage,
    onLongPress: () -> Unit = {},
    onRegenerate: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val bg = animateColorAsState(
        if (msg.fromUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "bubble"
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Column(Modifier.fillMaxWidth(0.85f)) {
                Box(
                    Modifier
                        .combinedClickableCompat(onClick = { if (onRegenerate != null) showMenu = !showMenu },
                            onLongClick = onLongPress)
                        .background(bg.value, RoundedCornerShape(24.dp))
                        .padding(14.dp)
                ) {
                    // Markdown poziom blokowy: kod, tabele, naglowki, listy, cytaty.
                    val shown = (msg.text + if (msg.streaming) "▍" else "")
                    MarkdownContent(shown, textColor = MaterialTheme.colorScheme.onSurface)
                    // wygenerowany obraz pod tekstem
                    msg.imageData?.let { dataUrl ->
                        Spacer(Modifier.height(8.dp))
                        DataUrlImage(dataUrl = dataUrl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentDesc = "Wygenerowany obraz")
                    }
                }
            }
            // mini-menu akcji nad odpowiedzia bota (tap)
            if (showMenu && onRegenerate != null) {
                androidx.compose.material3.Card(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = (-8).dp)
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .combinedClickableCompat(onClick = {
                                    showMenu = false
                                    onRegenerate()
                                })
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(painterResource(R.drawable.lucide_ic_refresh_cw), contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Regeneruj", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .combinedClickableCompat(onClick = {
                                    showMenu = false
                                    onLongPress()
                                })
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(painterResource(R.drawable.lucide_ic_copy), contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Kopiuj", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Sugestie promptow na pustym czacie (chipsy jak w Groku). */
@Composable
fun SuggestionChips(botName: String, onPick: (String) -> Unit) {
    val suggestions = remember(botName) {
        listOf(
            "Wyjaśnij mi prostymi słowami…" to "Wyjaśnij mi prostymi słowami, czym zajmuje się projekt Draco Nest.",
            "Zaprojektuj…" to "Zaprojektuj plan tygodnia na najbliższe 7 dni z 3 priorytetami dziennie.",
            "Napisz kod…" to "Napisz w Kotlinie funkcję debounce z korutynami i wyjaśnij ją.",
            "Podsumuj…" to "Streść kluczowe punkty ostatniej rozmowy w 5 punktach."
        )
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            "Zacznij od czegoś:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        suggestions.forEach { (label, prompt) ->
            androidx.compose.material3.Card(
                shape = RoundedCornerShape(18.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .combinedClickableCompat(onClick = { onPick(prompt) })
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/** combinedClickable wymaga ExperimentalFoundationApi — opakowanie. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit = {}, onLongClick: () -> Unit = {}): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/**
 * Panel "myslenia" jak w desktopie: status procesu + akumulowany reasoning,
 * zwijany (klik w naglowek). Rozwiniety pokazuje ostatnie ~10 linii reasoning.
 */
@Composable
fun ThinkingPanel(
    reasoning: String,
    status: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                RoundedCornerShape(16.dp)
            )
            .combinedClickableCompat(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // pulsujaca kropka "pracuje"
            val alpha = rememberInfiniteTransition(label = "pulse")
                .animateFloat(
                    initialValue = 0.35f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700),
                        repeatMode = RepeatMode.Reverse
                    ), label = "a"
                ).value

            Box(
                Modifier.size(8.dp).background(
                    MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape
                )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    reasoning.isNotBlank() -> if (expanded) "Myśli — dotknij, by zwinąć" else "Myśli — dotknij, by rozwinąć"
                    status.isNotBlank() -> status
                    else -> "Myśli…"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded && reasoning.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            // ostatnie linie reasoningu — pelna historia jest za dluga na telefon
            val lines = reasoning.trim().lines()
            val tail = lines.takeLast(12).joinToString("\n")
            Text(
                markdownToAnnotated(tail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 14,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

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
