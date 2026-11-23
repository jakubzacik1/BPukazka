package app.impulseblocker

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.impulseblocker.data.BackupManager
import app.impulseblocker.data.ConfigRepository
import app.impulseblocker.data.PhotoStore
import app.impulseblocker.data.UnlockRepository
import app.impulseblocker.service.Notifications
import app.impulseblocker.util.AndroidClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BlockerApp : Application() {

    /** Lives as long as the process (which is kept alive by the system-bound services). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var unlocks: UnlockRepository
        private set
    lateinit var config: ConfigRepository
        private set
    lateinit var photos: PhotoStore
        private set
    lateinit var backups: BackupManager
        private set

    override fun onCreate() {
        super.onCreate()
        unlocks = UnlockRepository(this, appScope, AndroidClock(contentResolver))
        config = ConfigRepository(this, appScope, unlocks)
        photos = PhotoStore(this)
        backups = BackupManager(this, config, photos)
        Notifications.createChannels(this)
        registerShutdownReceiver()
    }

    /**
     * On a clean shutdown, freeze the remaining time of running countdowns and
     * temporary unlocks so they resume (not restart / not extend) after boot.
     */
    private fun registerShutdownReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pending = goAsync()
                appScope.launch {
                    try {
                        unlocks.pauseForShutdown()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_SHUTDOWN), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

val Context.app: BlockerApp get() = applicationContext as BlockerApp
