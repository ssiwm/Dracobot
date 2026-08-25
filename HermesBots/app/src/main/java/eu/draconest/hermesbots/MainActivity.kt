package eu.draconest.hermesbots

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.draconest.hermesbots.data.AppStore
import eu.draconest.hermesbots.data.AppViewModel
import eu.draconest.hermesbots.data.CrashGuard
import eu.draconest.hermesbots.data.HermesMessagingService
import eu.draconest.hermesbots.ui.ChatScreen
import eu.draconest.hermesbots.ui.ConnectScreen
import eu.draconest.hermesbots.ui.RosterScreen
import eu.draconest.hermesbots.ui.RoutinesScreen
import eu.draconest.hermesbots.ui.SessionPickerScreen
import eu.draconest.hermesbots.ui.theme.HermesBotsTheme

class MainActivity : ComponentActivity() {
    lateinit var store: AppStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore(this)
        val lastCrash = CrashGuard.install(this)
        setContent {
            HermesBotsTheme {
                var crashReport by remember { mutableStateOf(lastCrash) }
                if (crashReport != null) {
                    CrashReportScreen(
                        report = crashReport!!,
                        onDismiss = { crashReport = null }
                    )
                } else {
                    AppRoot(appStore = store)
                }
            }
        }
    }
}

@Composable
private fun rememberPermissionState(): androidx.activity.compose.ManagedActivityResultLauncher<String, Boolean> {
    return androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
}

@Composable
private fun CrashReportScreen(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Ups — poprzednie uruchomienie padło 💥", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth()
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                clipboard.setText(AnnotatedString(report))
                onDismiss()
            }) {
                Text("Kopiuj i kontynuuj")
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel = viewModel(), appStore: AppStore) {
    val connected by vm.connected.collectAsState()
    val connecting by vm.connecting.collectAsState()
    val error by vm.connectionError.collectAsState()
    val bots by vm.bots.collectAsState()
    val messages by vm.messages.collectAsState()
    val activeBot by vm.activeBot.collectAsState()
    val thinking by vm.thinking.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val routines by vm.routines.collectAsState()
    val viewRoutines by vm.viewRoutines.collectAsState()

    // podlacz trwaly store + auto-connect + obserwacja linku WS
    remember {
        vm.store = appStore; vm.autoConnect(); vm.observeLink()
        HermesMessagingService.registerTokenOnBridge(appStore.context())
        true
    }

    // Android 13+: zgoda na powiadomienia (raz)
    val notifPermission = rememberPermissionState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // auto-reconnect po powrocie apki z tla / zmianie sieci
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onNetworkMaybeRestored()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val offline by vm.offline.collectAsState()

    var url by remember { mutableStateOf(appStore.url) }
    var username by remember { mutableStateOf(appStore.username) }
    var password by remember { mutableStateOf("") }

    when {
        !connected -> ConnectScreen(
            url = url,
            onUrlChange = { url = it },
            username = username,
            onUsernameChange = { username = it },
            password = password,
            onPasswordChange = { password = it },
            status = if (connecting) "Łączenie…" else null,
            error = error,
            busy = connecting,
            onConnect = { vm.connect(url, username, password) }
        )
        activeBot == null -> RosterScreen(bots = bots, onOpen = vm::openChat)
        viewRoutines -> RoutinesScreen(
            bot = activeBot!!,
            routines = routines,
            onToggle = vm::toggleRoutine,
            onBack = vm::closeRoutines
        )
        sessions.isNotEmpty() -> SessionPickerScreen(
            bot = activeBot!!,
            sessions = sessions,
            onResume = vm::resumeChat,
            onNew = vm::startNewSession,
            onBack = vm::closeChat
        )
        else -> ChatScreen(
            bot = activeBot!!,
            messages = messages,
            thinking = thinking,
            offline = offline,
            onSend = vm::send,
            onBack = vm::closeChat,
            onRoutines = vm::openRoutines
        )
    }
}
