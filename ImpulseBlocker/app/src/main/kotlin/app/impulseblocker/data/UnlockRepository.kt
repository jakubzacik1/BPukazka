package app.impulseblocker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.impulseblocker.core.MonotonicClock
import app.impulseblocker.core.RuntimeState
import app.impulseblocker.core.UnlockLogic
import app.impulseblocker.core.UnlockSettings
import app.impulseblocker.core.UnlockTarget
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Temporary unlocks and the running unlock challenge. Stored in
 * noBackupFilesDir: never backed up, never restored onto another device.
 * Until the file is loaded the in-memory state is empty, i.e. everything is
 * blocked (fail closed).
 */
class UnlockRepository(
    context: Context,
    scope: CoroutineScope,
    val clock: MonotonicClock,
) {
    private val store: DataStore<RuntimeState> = DataStoreFactory.create(
        serializer = JsonSerializer(RuntimeState.serializer(), RuntimeState()),
        corruptionHandler = ReplaceFileCorruptionHandler { RuntimeState() },
        scope = scope,
        produceFile = { File(context.noBackupFilesDir, "runtime_state.json") },
    )

    val state: StateFlow<RuntimeState> = store.data.stateIn(scope, SharingStarted.Eagerly, RuntimeState())

    init {
        // Handle deadlines written during a previous boot.
        scope.launch { store.updateData { UnlockLogic.reconcile(it, clock.now()) } }
    }

    fun isUnlocked(target: UnlockTarget): Boolean =
        UnlockLogic.isUnlocked(state.value, target, clock.now())

    fun remainingMs(target: UnlockTarget): Long =
        UnlockLogic.remainingUnlockMs(state.value, target, clock.now())

    suspend fun startChallenge(target: UnlockTarget, settings: UnlockSettings) {
        store.updateData { UnlockLogic.startChallenge(it, target, settings, clock.now()) }
    }

    suspend fun completeMath() {
        store.updateData { UnlockLogic.completeMath(it, clock.now()) }
    }

    suspend fun confirm() {
        store.updateData { UnlockLogic.confirm(it, clock.now()) }
    }

    suspend fun cancelChallenge() {
        store.updateData { UnlockLogic.cancelChallenge(it) }
    }

    suspend fun revoke(target: UnlockTarget) {
        store.updateData { UnlockLogic.revoke(it, target) }
    }

    suspend fun pruneExpired() {
        store.updateData { UnlockLogic.pruneExpired(it, clock.now()) }
    }

    suspend fun pauseForShutdown() {
        store.updateData { UnlockLogic.pauseForShutdown(it, clock.now()) }
    }
}
