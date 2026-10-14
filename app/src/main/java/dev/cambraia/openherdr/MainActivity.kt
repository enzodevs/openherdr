package dev.cambraia.openherdr

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cambraia.openherdr.data.Connection
import dev.cambraia.openherdr.ui.AgentScreen
import dev.cambraia.openherdr.ui.DashboardScreen
import dev.cambraia.openherdr.ui.DeviceKeyInfo
import dev.cambraia.openherdr.ui.DeviceKeyScreen
import dev.cambraia.openherdr.ui.NavViewModel
import dev.cambraia.openherdr.ui.OpenHerdrTheme
import dev.cambraia.openherdr.ui.Screen
import dev.cambraia.openherdr.ui.ServerEditorScreen
import dev.cambraia.openherdr.ui.ServersScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val nav: NavViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
        setContent { OpenHerdrTheme { Root(nav) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Notification taps carry the pane that needs attention. */
    private fun handle(intent: Intent?) {
        val pane = intent?.getStringExtra(EXTRA_PANE_ID) ?: return
        intent.removeExtra(EXTRA_PANE_ID)
        if (app.connections.connection.value != Connection.Idle) {
            nav.push(Screen.Dashboard)
            nav.showOverDashboard(Screen.Agent(pane))
        }
    }

    companion object {
        const val EXTRA_PANE_ID = "pane_id"
    }
}

@Composable
private fun Root(nav: NavViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.app
    val store by app.store.state.collectAsStateWithLifecycle()
    val connection by app.connections.connection.collectAsStateWithLifecycle()
    val snapshot by app.connections.snapshot.collectAsStateWithLifecycle()

    // Disconnected elsewhere (for example from the notification): leave the live screens.
    LaunchedEffect(connection) {
        if (connection == Connection.Idle && (nav.current == Screen.Dashboard || nav.current is Screen.Agent)) {
            nav.popTo(Screen.Servers)
        }
    }

    BackHandler(enabled = nav.stack.size > 1) {
        if (nav.current == Screen.Dashboard) app.disconnect()
        nav.pop()
    }

    AnimatedContent(
        targetState = nav.current,
        transitionSpec = {
            val direction = if (nav.forward) 1 else -1
            (slideInHorizontally { it / 4 * direction } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * direction } + fadeOut())
        },
        label = "screens",
    ) { screen -> ScreenContent(screen, nav, app, store, connection, snapshot) }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    nav: NavViewModel,
    app: OpenHerdrApp,
    store: dev.cambraia.openherdr.data.StoreState,
    connection: Connection,
    snapshot: dev.cambraia.openherdr.data.Snapshot,
) {
    when (screen) {
        Screen.Servers -> ServersScreen(
            profiles = store.profiles,
            onConnect = { profile ->
                app.connect(profile)
                nav.push(Screen.Dashboard)
            },
            onEdit = { nav.push(Screen.Editor(it?.id)) },
            onDeviceKey = { nav.push(Screen.DeviceKey) },
        )

        is Screen.Editor -> {
            val existing = store.profiles.firstOrNull { it.id == screen.profileId }
            ServerEditorScreen(
                existing = existing,
                pinned = existing?.let { store.pins[it.target.hostId] },
                onSave = { app.store.upsert(it); nav.pop() },
                onDelete = { app.store.delete(it.id); nav.pop() },
                onForgetHostKey = { app.store.remove(it.target.hostId) },
                onBack = { nav.pop() },
            )
        }

        Screen.DeviceKey -> {
            var info by remember { mutableStateOf<DeviceKeyInfo?>(null) }
            var generation by remember { mutableStateOf(0) }
            LaunchedEffect(generation) {
                info = withContext(Dispatchers.Default) {
                    if (generation > 0) app.deviceKey.regenerate()
                    val identity = app.deviceKey.identity()
                    DeviceKeyInfo(identity.authorizedKeyLine, identity.fingerprint, app.deviceKey.protection())
                }
            }
            DeviceKeyScreen(info = info, onRegenerate = { info = null; generation++ }, onBack = { nav.pop() })
        }

        Screen.Dashboard -> DashboardScreen(
            connection = connection,
            snapshot = snapshot,
            onOpenAgent = { nav.push(Screen.Agent(it.paneId)) },
            onRetry = { connection.activeProfile?.let(app::connect) },
            onDeviceKey = { nav.push(Screen.DeviceKey) },
            onTrust = { app.connections.trust(it) },
            onDisconnect = {
                app.disconnect()
                nav.popTo(Screen.Servers)
            },
        )

        is Screen.Agent -> AgentScreen(
            paneId = screen.paneId,
            agent = snapshot.agents.firstOrNull { it.paneId == screen.paneId },
            revision = snapshot.revision,
            read = { app.connections.read(it) },
            submit = { pane, text -> app.connections.submit(pane, text) },
            sendKeys = { pane, keys -> app.connections.sendKeys(pane, keys) },
            onBack = { nav.pop() },
        )
    }
}
