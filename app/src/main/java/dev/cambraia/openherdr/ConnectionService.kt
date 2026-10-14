package dev.cambraia.openherdr

import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.cambraia.openherdr.data.Connection
import dev.cambraia.openherdr.data.Snapshot
import dev.cambraia.openherdr.protocol.AgentStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process (and so the SSH link) alive while connected, showing a quiet ongoing
 * notification. Stops itself as soon as the connection goes idle.
 */
class ConnectionService : LifecycleService() {
    private var observer: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_DISCONNECT) {
            app.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, Notifier.CONNECTION_NOTIFICATION_ID,
            app.notifier.connectionNotification(getString(R.string.app_name), getString(R.string.status_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (observer == null) {
            observer = lifecycleScope.launch {
                combine(app.connections.connection, app.connections.snapshot, ::Pair).collect { (connection, snapshot) ->
                    if (connection == Connection.Idle) {
                        stopSelf()
                    } else {
                        app.notifier.updateConnection(
                            connection.activeProfile?.name ?: getString(R.string.app_name),
                            describe(connection, snapshot),
                        )
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun describe(connection: Connection, snapshot: Snapshot): String = when (connection) {
        is Connection.Connected -> {
            val count = snapshot.agents.size
            val blocked = snapshot.agents.count { it.status == AgentStatus.Blocked }
            resources.getQuantityString(R.plurals.status_agents, count, count) +
                if (blocked > 0) " · " + getString(R.string.status_blocked, blocked) else ""
        }
        is Connection.Failed -> if (connection.retrying) getString(R.string.status_reconnecting) else connection.message
        is Connection.NeedsTrust, is Connection.Refused -> getString(R.string.status_attention)
        else -> getString(R.string.status_connecting)
    }

    companion object {
        const val ACTION_DISCONNECT = "dev.cambraia.openherdr.DISCONNECT"
    }
}
