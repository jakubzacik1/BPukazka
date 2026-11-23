package app.impulseblocker.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(val packageName: String, val label: String)

/**
 * Installed-app lookups. Uses only the <queries> declared in the manifest
 * (launchable apps), not QUERY_ALL_PACKAGES.
 */
object AppCatalog {

    /** Packages that are never blocked: system UI, settings, phone/emergency, launchers, keyboards. */
    private val ALWAYS_ALLOWED = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.incallui",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.vpndialogs",
        "com.google.android.apps.nexuslauncher",
    )

    @Volatile private var cachedProtected: Set<String>? = null

    fun protectedPackages(context: Context): Set<String> {
        cachedProtected?.let { return it }
        val pm = context.packageManager
        val set = HashSet(ALWAYS_ALLOWED)
        set += context.packageName
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(home, 0).forEach { set += it.activityInfo.packageName }
        runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { set += it }
        }
        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.inputMethodList?.forEach { set += it.packageName }
        }
        return set.also { cachedProtected = it }
    }

    fun isProtected(context: Context, pkg: String) = pkg in protectedPackages(context)

    /** Launchable, user-facing apps that may be blocked. Call off the main thread. */
    fun launchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val protected = protectedPackages(context)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first !in protected }
            .map { AppEntry(it.first, it.second) }
            .sortedBy { it.label.lowercase() }
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    fun icon(context: Context, pkg: String, sizePx: Int): Bitmap? = try {
        context.packageManager.getApplicationIcon(pkg).toBitmap(sizePx, sizePx)
    } catch (e: Exception) {
        null
    }

    fun launchIntent(context: Context, pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)
}
