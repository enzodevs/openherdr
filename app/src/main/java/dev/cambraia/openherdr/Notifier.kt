package dev.cambraia.openherdr

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.cambraia.openherdr.data.AgentAlert
import dev.cambraia.openherdr.data.ServerProfile

class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun connectionNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_openherdr)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(null))
            .addAction(0, context.getString(R.string.disconnect), disconnectIntent())
            .build()

    fun updateConnection(title: String, text: String) {
        if (!canPost()) return
        @Suppress("MissingPermission") // checked by canPost()
        manager.notify(CONNECTION_NOTIFICATION_ID, connectionNotification(title, text))
    }

    fun alert(profile: ServerProfile, alerts: List<AgentAlert>) {
        if (!canPost()) return
        alerts.forEach { alert ->
            val text = when (alert.kind) {
                AgentAlert.Kind.NeedsInput -> context.getString(R.string.alert_needs_input, alert.agent.label)
                AgentAlert.Kind.Finished -> context.getString(R.string.alert_finished, alert.agent.label)
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_openherdr)
                .setContentTitle(text)
                .setContentText(alert.agent.subtitle ?: profile.name)
                .setSubText(profile.name)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setGroup(GROUP_ALERTS)
                .setContentIntent(openApp(alert.agent.paneId))
                .build()
            @Suppress("MissingPermission") // checked by canPost()
            manager.notify(TAG_ALERT, alert.agent.paneId.hashCode(), notification)
        }
    }

    fun clearAlerts() = manager.activeNotifications
        .filter { it.tag == TAG_ALERT }
        .forEach { manager.cancel(it.tag, it.id) }

    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(paneId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { paneId?.let { putExtra(MainActivity.EXTRA_PANE_ID, it) } }
        return PendingIntent.getActivity(
            context, paneId?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun disconnectIntent(): PendingIntent = PendingIntent.getService(
        context, 1,
        Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_DISCONNECT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ALERTS = "agent_alerts"
        const val CHANNEL_CONNECTION = "connection"
        const val CONNECTION_NOTIFICATION_ID = 1
        private const val TAG_ALERT = "alert"
        private const val GROUP_ALERTS = "agent_alerts"
    }
}
