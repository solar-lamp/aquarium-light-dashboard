package com.solarlamp.aquariumlight

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Notification channels and the notifications the app posts. */
class Alerts(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    fun monitorNotification(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppIntent())
            .build()

    fun updateMonitor(text: String) = post(ID_MONITOR, monitorNotification(text))

    fun deviceOnline(online: Boolean) {
        val builder = alert(CHANNEL_ONLINE)

        if (online) {
            builder.setContentTitle(context.getString(R.string.alert_online_title))
        } else {
            val text = context.getString(R.string.alert_offline_text)
            builder.setContentTitle(context.getString(R.string.alert_offline_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

        post(ID_ONLINE, builder.build())
    }

    fun lightChanged(on: Boolean, mode: String?) {
        val builder = alert(CHANNEL_LIGHT).setContentTitle(
            context.getString(if (on) R.string.alert_light_on else R.string.alert_light_off))

        if (mode != null) {
            builder.setContentText(context.getString(R.string.alert_mode_text, modeLabel(mode)))
        }

        post(ID_LIGHT, builder.build())
    }

    fun modeChanged(mode: String) {
        post(ID_MODE, alert(CHANNEL_MODE)
            .setContentTitle(context.getString(R.string.alert_mode_title, modeLabel(mode)))
            .build())
    }

    private fun alert(channel: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())

    private fun post(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        if (allowed) {
            manager.notify(id, notification)
        }
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)

        system.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_MONITOR,
                context.getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_MIN),
            NotificationChannel(CHANNEL_ONLINE,
                context.getString(R.string.channel_online), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_LIGHT,
                context.getString(R.string.channel_light), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_MODE,
                context.getString(R.string.channel_mode), NotificationManager.IMPORTANCE_LOW)
        ))
    }

    companion object {
        const val ID_MONITOR = 1
        private const val ID_ONLINE = 10
        private const val ID_LIGHT = 11
        private const val ID_MODE = 12

        private const val CHANNEL_MONITOR = "monitor"
        private const val CHANNEL_ONLINE = "device_online"
        private const val CHANNEL_LIGHT = "light"
        private const val CHANNEL_MODE = "mode"

        fun modeLabel(mode: String): String = when (mode) {
            "AUTO" -> "Auto"
            "FORCE_ON" -> "Forced on"
            "FORCE_OFF" -> "Forced off"
            else -> mode
        }
    }
}
