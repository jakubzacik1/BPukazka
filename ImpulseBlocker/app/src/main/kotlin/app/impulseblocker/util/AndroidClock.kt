package app.impulseblocker.util

import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings
import app.impulseblocker.core.MonoInstant
import app.impulseblocker.core.MonotonicClock

/**
 * Monotonic clock: elapsedRealtime() keeps counting in deep sleep and is not
 * affected by changing the date/time in Settings. Settings.Global.BOOT_COUNT
 * tells boots apart (elapsed time restarts from zero after a reboot).
 */
class AndroidClock(private val resolver: ContentResolver) : MonotonicClock {
    private val bootCount: Int by lazy {
        Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0)
    }

    override fun now(): MonoInstant = MonoInstant(bootCount, SystemClock.elapsedRealtime())
}
