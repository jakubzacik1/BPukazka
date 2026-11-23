package app.impulseblocker.service

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
import app.impulseblocker.R
import app.impulseblocker.ui.MainActivity

object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_WARNINGS = "warnings"
    const val CHANNEL_BLOCKED = "blocked_sites"

    const val ID_VPN_ONGOING = 1
    const val ID_VPN_STOPPED = 2
    const val ID_ACCESSIBILITY_STOPPED = 3
    private const val ID_BLOCKED_BASE = 1000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_STATUS, "Protection status", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Ongoing notification while website blocking runs (required by Android)."
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_WARNINGS, "Protection warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tells you when app or website blocking stops."
                },
                NotificationChannel(CHANNEL_BLOCKED, "Blocked websites", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Shown when a blocked website is opened, with a way to request a temporary unlock."
                },
            ),
        )
    }

    fun openAppIntent(context: Context, requestCode: Int = 0, extras: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply(extras)
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun vpnOngoing(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Website blocking is active")
            .setContentText("DNS filtering runs locally on this phone.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(context))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun warn(context: Context, id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notify(context, id, n)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun blockedDomain(context: Context, rule: String, host: String) {
        val id = ID_BLOCKED_BASE + (rule.hashCode() and 0xFFFF)
        val pi = openAppIntent(context, id) {
            putExtra(MainActivity.EXTRA_UNLOCK_TYPE, "DOMAIN")
            putExtra(MainActivity.EXTRA_UNLOCK_ID, rule)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_BLOCKED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Blocked: $rule")
            .setContentText("$host was blocked. Tap to request a temporary unlock.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        notify(context, id, n)
    }

    private fun notify(context: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
