package app.impulseblocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import app.impulseblocker.app
import kotlinx.coroutines.launch

/**
 * Restarts website blocking after a reboot or an app update.
 * (App blocking needs nothing here: Android rebinds enabled accessibility
 * services automatically. If "Always-on VPN" is enabled for this app in
 * Android settings, the system also starts the VPN on its own.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.app
        app.appScope.launch {
            try {
                val cfg = app.config.current()
                if (cfg.webBlockingEnabled && VpnService.prepare(context) == null) {
                    DnsVpnService.start(context)
                }
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not restart website blocking", e)
            } finally {
                pending.finish()
            }
        }
    }
}
