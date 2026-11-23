package app.impulseblocker.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.impulseblocker.app
import app.impulseblocker.core.DnsFilter
import app.impulseblocker.core.UnlockTarget
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class VpnStatus(
    val running: Boolean = false,
    val alwaysOn: Boolean = false,
    val lockdown: Boolean = false,
    val stopReason: String? = null,
    val upstreamServers: List<String> = emptyList(),
    val privateDnsStrict: Boolean = false,
    val blockedQueries: Long = 0,
)

/**
 * Local DNS-filtering VPN. Nothing is sent to any server operated by this app;
 * allowed queries go to the DNS server of the network the phone is using, as
 * they would without the app.
 *
 * Routing: the VPN only claims two virtual DNS server addresses (one IPv4, one
 * IPv6). Android sends DNS lookups to them; all other traffic keeps using the
 * normal network directly (split tunnel), so connectivity and battery are
 * unaffected.
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "DnsVpn"
        private const val ACTION_STOP = "app.impulseblocker.action.STOP_VPN"

        const val V4_ADDRESS = "10.111.222.1"
        const val V4_DNS = "10.111.222.2"
        const val V6_ADDRESS = "fd6b:6c6f:636b::1"
        const val V6_DNS = "fd6b:6c6f:636b::2"

        /** Used when the network reports no DNS server (rare). */
        private val FALLBACK_DNS = listOf("1.1.1.1", "9.9.9.9")

        private val _status = MutableStateFlow(VpnStatus())
        val status: StateFlow<VpnStatus> = _status.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DnsVpnService::class.java))
        }

        fun stop(context: Context) {
            if (!_status.value.running) return
            context.startService(Intent(context, DnsVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private var engine: DnsTunnelEngine? = null
    private var scope: CoroutineScope? = null
    private var userStopped = false
    @Volatile private var filter = DnsFilter(emptyList(), true)
    @Volatile private var upstream: List<InetAddress> = emptyList()
    private val lastNotified = ConcurrentHashMap<String, Long>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            userStopped = true
            stopFiltering("Stopped by you")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        startFiltering()
        return START_STICKY
    }

    private fun goForeground() {
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_VPN_ONGOING, Notifications.vpnOngoing(this),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            // The VPN is still kept alive by the system binding; only the
            // status notification is missing in this case.
            Log.w(TAG, "startForeground not allowed", e)
        }
    }

    private fun startFiltering() {
        if (engine != null) return
        if (prepare(this) != null) {
            _status.value = VpnStatus(stopReason = "VPN permission is not granted")
            stopSelf()
            return
        }
        userStopped = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { this.scope = it }
        val app = this.app

        // Keep the filter snapshot in sync with the configuration.
        scope.launch {
            app.config.config
                .map { it.blockedDomains to it.settings.blockEncryptedDns }
                .distinctUntilChanged()
                .collect { (domains, blockDoh) -> filter = DnsFilter(domains, blockDoh) }
        }
        watchUnderlyingNetwork()

        val builder = Builder()
            .setSession("Impulse Blocker website filter")
            .setMtu(1500)
            .setBlocking(true)
            .addAddress(V4_ADDRESS, 32)
            .addDnsServer(V4_DNS)
            .addRoute(V4_DNS, 32)
            .addAddress(V6_ADDRESS, 128)
            .addDnsServer(V6_DNS)
            .addRoute(V6_DNS, 128)
            .setConfigureIntent(Notifications.openAppIntent(this))
        // Our own upstream lookups must not loop back into the tunnel.
        runCatching { builder.addDisallowedApplication(packageName) }
        // VPNs are treated as metered by default on Android 10+; follow the real network instead.
        builder.setMetered(false)

        val tun = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        if (tun == null) {
            _status.value = VpnStatus(stopReason = "Android refused to start the VPN (is another VPN app set as always-on?)")
            stopFiltering(null)
            stopSelf()
            return
        }
        engine = DnsTunnelEngine(
            tun = tun,
            protect = { protect(it) },
            upstreamServers = { upstream.ifEmpty { FALLBACK_DNS.map(InetAddress::getByName) } },
            decide = { q -> filter.decide(q) { rule -> app.unlocks.isUnlocked(UnlockTarget.domain(rule)) } },
            onBlocked = ::onBlocked,
        ).also { it.start() }

        _status.value = _status.value.copy(
            running = true,
            alwaysOn = isAlwaysOn,
            lockdown = isLockdownEnabled,
            stopReason = null,
        )
        Notifications.cancel(this, Notifications.ID_VPN_STOPPED)
        Log.i(TAG, "Website filter started")
    }

    private fun watchUnderlyingNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        // This app is excluded from its own VPN, so its default network is the
        // real (Wi-Fi / mobile) network whose DNS servers we forward to.
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = update(lp)
            override fun onLost(network: Network) {
                upstream = emptyList()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
        cm.activeNetwork?.let { cm.getLinkProperties(it) }?.let(::update)
    }

    private fun update(lp: LinkProperties) {
        upstream = lp.dnsServers.filterNot { it.hostAddress == V4_DNS || it.hostAddress == V6_DNS }
        _status.value = _status.value.copy(
            upstreamServers = upstream.mapNotNull { it.hostAddress },
            privateDnsStrict = lp.privateDnsServerName != null,
        )
    }

    private fun onBlocked(rule: String, host: String) {
        _status.value = _status.value.copy(blockedQueries = _status.value.blockedQueries + 1)
        // At most one notification per domain rule per minute.
        val now = SystemClock.elapsedRealtime()
        val last = lastNotified[rule]
        if (last == null || now - last > 60_000) {
            lastNotified[rule] = now
            Notifications.blockedDomain(this, rule, host)
        }
    }

    private fun stopFiltering(reason: String?) {
        engine?.stop()
        engine = null
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        scope?.cancel()
        scope = null
        val wasRunning = _status.value.running
        _status.value = _status.value.copy(
            running = false,
            stopReason = if (wasRunning) reason ?: _status.value.stopReason else _status.value.stopReason,
        )
        if (wasRunning && !userStopped && app.config.config.value.webBlockingEnabled) {
            Notifications.warn(
                this, Notifications.ID_VPN_STOPPED,
                "Website blocking stopped",
                (reason ?: "The VPN was stopped.") + " Open Impulse Blocker to turn it back on.",
            )
        }
    }

    /** Called when the user disconnects the VPN in Settings or another VPN app takes over. */
    override fun onRevoke() {
        stopFiltering("The VPN was disconnected by Android settings or replaced by another VPN app.")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopFiltering(if (userStopped) "Stopped by you" else "The VPN service was stopped.")
        super.onDestroy()
    }
}
