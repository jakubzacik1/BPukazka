package app.impulseblocker.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import app.impulseblocker.service.AppBlockerAccessibilityService
import app.impulseblocker.service.DnsVpnService

/** Snapshot of everything the dashboard needs to show about protection health. */
data class ProtectionStatus(
    val accessibilityEnabled: Boolean,
    val accessibilityRunning: Boolean,
    val vpnPermission: Boolean,
    val vpnRunning: Boolean,
    val vpnAlwaysOn: Boolean,
    val vpnLockdown: Boolean,
    val vpnStopReason: String?,
    val otherVpnActive: Boolean,
    val privateDnsStrict: Boolean,
    val batteryUnrestricted: Boolean,
    val notificationsAllowed: Boolean,
) {
    companion object {
        fun read(context: Context): ProtectionStatus {
            val vpn = DnsVpnService.status.value
            val cm = context.getSystemService(ConnectivityManager::class.java)
            // This app is excluded from its own VPN, so activeNetwork is the real network.
            val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) }
            @Suppress("DEPRECATION")
            val otherVpn = !vpn.running && cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            return ProtectionStatus(
                accessibilityEnabled = isAccessibilityEnabled(context),
                accessibilityRunning = AppBlockerAccessibilityService.running.value,
                vpnPermission = VpnService.prepare(context) == null,
                vpnRunning = vpn.running,
                vpnAlwaysOn = vpn.alwaysOn,
                vpnLockdown = vpn.lockdown,
                vpnStopReason = vpn.stopReason,
                otherVpnActive = otherVpn,
                privateDnsStrict = lp?.privateDnsServerName != null || vpn.privateDnsStrict,
                batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName),
                notificationsAllowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
            )
        }

        fun isAccessibilityEnabled(context: Context): Boolean {
            val expected = ComponentName(context, AppBlockerAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            return splitter.any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
