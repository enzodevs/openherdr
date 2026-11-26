package dev.cambraia.openherdr

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
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
import dev.cambraia.openherdr.data.ConnectionManager
import dev.cambraia.openherdr.data.Topology
import dev.cambraia.openherdr.ui.TreeActions
import dev.cambraia.openherdr.ui.AgentScreen
import dev.cambraia.openherdr.ui.AgentsTab
import dev.cambraia.openherdr.ui.HomeScreen
import dev.cambraia.openherdr.ui.SettingsInfo
import dev.cambraia.openherdr.ui.SettingsTab
import dev.cambraia.openherdr.ui.Tab
import dev.cambraia.openherdr.ui.TabBarClearance
import dev.cambraia.openherdr.data.DeviceKey
import dev.cambraia.openherdr.data.ServerProfile
import dev.cambraia.openherdr.protocol.AgentStatus
import dev.cambraia.openherdr.voice.SpeechModel
import dev.cambraia.openherdr.ui.DeviceKeyInfo
import dev.cambraia.openherdr.ui.DeviceKeyScreen
import dev.cambraia.openherdr.ui.NavViewModel
import dev.cambraia.openherdr.ui.OpenHerdrTheme
import dev.cambraia.openherdr.ui.Screen
import dev.cambraia.openherdr.ui.ServerEditorScreen
import dev.cambraia.openherdr.ui.ServersTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val nav: NavViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
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
        if (app.connections.connection.value != Connection.Idle) nav.openAgent(pane)
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

    // Disconnected elsewhere (for example from the notification): leave the agent screen.
    LaunchedEffect(connection) {
        if (connection == Connection.Idle && nav.current is Screen.Agent) nav.home(Tab.Agents)
    }

    BackHandler(enabled = nav.stack.size > 1 || nav.tab != Tab.Agents) { nav.back() }

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
        Screen.Home -> HomeScreen(
            tab = nav.tab,
            blocked = snapshot.agents.count { it.status == AgentStatus.Blocked },
            onTab = { nav.tab = it },
        ) { tab ->
            val connect = { profile: ServerProfile ->
                app.connect(profile)
                nav.tab = Tab.Agents
            }
            when (tab) {
                Tab.Agents -> AgentsTab(
                    connection = connection,
                    snapshot = snapshot,
                    profiles = store.profiles,
                    bottomInset = TabBarClearance,
                    onConnect = connect,
                    onAddServer = { nav.push(Screen.Editor(null)) },
                    onOpenPane = { nav.push(Screen.Agent(it)) },
                    actions = remember(app) { TreeActionsImpl(app.connections) },
                    onRetry = { connection.activeProfile?.let(app::connect) },
                    onDeviceKey = { nav.push(Screen.DeviceKey) },
                    onTrust = { app.connections.trust(it) },
                    onDisconnect = { app.disconnect() },
                    listState = nav.treeScroll,
                    collapsed = nav.collapsed,
                )
                Tab.Servers -> ServersTab(
                    profiles = store.profiles,
                    liveId = (connection as? Connection.Connected)?.profile?.id,
                    bottomInset = TabBarClearance,
                    onConnect = connect,
                    onEdit = { nav.push(Screen.Editor(it?.id)) },
                )
                Tab.Settings -> {
                    var info by remember { mutableStateOf(SettingsInfo(null, null, false, "", BuildConfig.VERSION_NAME)) }
                    LaunchedEffect(Unit) {
                        info = withContext(Dispatchers.Default) {
                            val identity = app.deviceKey.identity()
                            val model = SpeechModel.locate(app)
                            SettingsInfo(
                                keyFingerprint = identity.fingerprint,
                                keyProtection = app.deviceKey.protection().label(),
                                voiceInstalled = model.installed,
                                voicePath = model.dir.path,
                                version = BuildConfig.VERSION_NAME,
                            )
                        }
                    }
                    SettingsTab(info, bottomInset = TabBarClearance, onDeviceKey = { nav.push(Screen.DeviceKey) })
                }
            }
        }

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

        is Screen.Agent -> AgentScreen(
            paneId = screen.paneId,
            node = remember(snapshot) { Topology.find(Topology.build(snapshot), screen.paneId) },
            home = snapshot.home,
            revision = snapshot.revision,
            files = { app.connections.files },
            dictation = app.dictation,
            drafts = app.drafts,
            draftKey = "${connection.activeProfile?.id}/${screen.paneId}",
            online = connection is Connection.Connected,
            read = { app.connections.read(it) },
            submit = { pane, text -> app.connections.submit(pane, text) },
            sendKeys = { pane, keys -> app.connections.sendKeys(pane, keys) },
            onRename = { label -> app.connections.change { renamePane(screen.paneId, label) } },
            onClose = { app.connections.change { closePane(screen.paneId) } },
            onBack = { nav.pop() },
        )
    }
}

private class TreeActionsImpl(private val connections: ConnectionManager) : TreeActions {
    override suspend fun createWorkspace(label: String?, cwd: String?, command: String?) = connections.createWorkspace(label, cwd, command)
    override suspend fun createTab(workspaceId: String, label: String?, command: String?) = connections.createTab(workspaceId, label, command)
    override suspend fun createPane(targetPaneId: String, direction: String, cwd: String?, label: String?, command: String?) =
        connections.createPane(targetPaneId, direction, cwd, label, command)
    override suspend fun renameWorkspace(workspaceId: String, label: String) = connections.change { renameWorkspace(workspaceId, label) }
    override suspend fun renameTab(tabId: String, label: String) = connections.change { renameTab(tabId, label) }
    override suspend fun closeWorkspace(workspaceId: String) = connections.change { closeWorkspace(workspaceId) }
    override suspend fun closeTab(tabId: String) = connections.change { closeTab(tabId) }
}

private fun DeviceKey.Protection.label(): String = when (this) {
    DeviceKey.Protection.StrongBox -> "StrongBox secure element"
    DeviceKey.Protection.TrustedEnvironment -> "Trusted Execution Environment"
    DeviceKey.Protection.Software -> "Software only"
    DeviceKey.Protection.Unknown -> "Unknown protection"
}
