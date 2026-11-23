package app.impulseblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import app.impulseblocker.app
import app.impulseblocker.core.UnlockLogic
import app.impulseblocker.core.UnlockTarget
import app.impulseblocker.ui.BlockActivity
import app.impulseblocker.util.AppCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Event-driven app blocking. Android delivers TYPE_WINDOW_STATE_CHANGED when a
 * window comes to the front; no polling is involved. When the window belongs to
 * a blocked package (and that package is not temporarily unlocked) the blocking
 * screen is started on top of it.
 *
 * The service is bound by the system, so it keeps running when the UI is closed
 * or removed from Recents, and Android rebinds it after a reboot.
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AppBlocker"
        private const val DEBOUNCE_MS = 600L

        /** True while the system has this service connected. */
        val running = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scope: CoroutineScope

    /** Last package that owned a full-screen window (ignores system UI / keyboards). */
    @Volatile private var foregroundPackage: String? = null
    private var lastBlockPackage: String? = null
    private var lastBlockAt = 0L
    private var receiverRegistered = false

    private val expiryCheck = Runnable { reevaluateForeground("unlock-expired") }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // After unlocking the screen the previous app may reappear without a
            // window-state event, so check it explicitly.
            reevaluateForeground(intent.action ?: "screen")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running.value = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        Notifications.cancel(this, Notifications.ID_ACCESSIBILITY_STOPPED)

        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        overlayPackages = buildSet {
            add("com.android.systemui")
            add("android")
            runCatching {
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.inputMethodList?.forEach { add(it.packageName) }
            }
        }

        val app = this.app
        // Re-check whenever unlocks or the blocked list change, and schedule a
        // single timer for the next unlock expiry (no periodic polling).
        scope.launch {
            combine(app.unlocks.state, app.config.config) { s, _ -> s }.collect { state ->
                handler.removeCallbacks(expiryCheck)
                UnlockLogic.nextExpiryInMs(state, app.unlocks.clock.now())?.let { ms ->
                    handler.postDelayed(expiryCheck, ms + 250)
                }
                reevaluateForeground("state-changed")
            }
        }
        // Recover website blocking after a reboot / process restart if it was enabled.
        scope.launch {
            val cfg = app.config.current()
            if (cfg.webBlockingEnabled && !DnsVpnService.status.value.running && VpnService.prepare(this@AppBlockerAccessibilityService) == null) {
                runCatching { DnsVpnService.start(this@AppBlockerAccessibilityService) }
                    .onFailure { Log.w(TAG, "Could not restart VPN from accessibility service", it) }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) {
            foregroundPackage = pkg
            return
        }
        // System UI (notification shade, lock screen), keyboards etc. are overlays;
        // they don't change which app is underneath.
        if (isOverlayPackage(pkg)) return
        foregroundPackage = pkg
        evaluate(pkg, fromEvent = true)
    }

    /** Keyboards and system overlays sit on top of an app without replacing it. */
    private var overlayPackages: Set<String> = setOf("com.android.systemui", "android")

    private fun isOverlayPackage(pkg: String): Boolean = pkg in overlayPackages

    private fun reevaluateForeground(reason: String) {
        val pkg = foregroundPackage ?: return
        if (pkg == packageName) return
        Log.d(TAG, "Re-evaluating $pkg ($reason)")
        evaluate(pkg, fromEvent = false)
    }

    private fun evaluate(pkg: String, fromEvent: Boolean) {
        val app = this.app
        if (!app.config.loaded.value) {
            // Configuration not read yet (process just started): check once it is.
            scope.launch {
                app.config.loaded.first { it }
                if (foregroundPackage == pkg) evaluate(pkg, fromEvent)
            }
            return
        }
        val cfg = app.config.config.value
        if (!cfg.appBlockingEnabled || pkg !in cfg.blockedApps) return
        if (AppCatalog.isProtected(this, pkg)) return
        if (app.unlocks.isUnlocked(UnlockTarget.app(pkg))) return

        val now = SystemClock.elapsedRealtime()
        if (fromEvent && pkg == lastBlockPackage && now - lastBlockAt < DEBOUNCE_MS) return
        lastBlockPackage = pkg
        lastBlockAt = now
        Log.i(TAG, "Blocking $pkg")
        startActivity(BlockActivity.intent(this, pkg))
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (!running.value) return
        running.value = false
        handler.removeCallbacks(expiryCheck)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        if (::scope.isInitialized) scope.cancel()
        if (app.config.config.value.appBlockingEnabled) {
            Notifications.warn(
                this, Notifications.ID_ACCESSIBILITY_STOPPED,
                "App blocking stopped",
                "The Impulse Blocker accessibility service was turned off or stopped by Android. Open the app to turn it back on.",
            )
        }
    }
}
