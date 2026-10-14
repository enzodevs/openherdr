package dev.cambraia.openherdr

import android.app.Application
import android.content.Intent
import dev.cambraia.openherdr.data.AppStore
import dev.cambraia.openherdr.data.ConnectionManager
import dev.cambraia.openherdr.data.DeviceKey
import dev.cambraia.openherdr.data.ServerProfile
import dev.cambraia.openherdr.ssh.SshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application-wide objects; a tiny manual dependency graph. */
class OpenHerdrApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store by lazy { AppStore(filesDir) }
    val deviceKey by lazy { DeviceKey() }
    val notifier by lazy { Notifier(this) }

    val connections by lazy {
        ConnectionManager(
            scope = scope,
            connector = { profile -> SshSession.connect(profile.target, deviceKey.identity(), store) },
            pins = store,
            onAlerts = { profile, alerts -> notifier.alert(profile, alerts) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
    }

    /** Connects and keeps the link alive in a foreground service so alerts keep arriving. */
    fun connect(profile: ServerProfile) {
        connections.connect(profile)
        startForegroundService(Intent(this, ConnectionService::class.java))
    }

    fun disconnect() {
        connections.disconnect()
        notifier.clearAlerts()
    }
}

val android.content.Context.app: OpenHerdrApp
    get() = applicationContext as OpenHerdrApp
