package app.impulseblocker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.impulseblocker.core.BlockerConfig
import app.impulseblocker.core.ConfigPolicy
import app.impulseblocker.core.UnlockTarget
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Persistent user configuration (blocked apps, domains, settings).
 * Stored in files/config/ which is included in Android Auto Backup.
 */
class ConfigRepository(
    context: Context,
    scope: CoroutineScope,
    private val unlocks: UnlockRepository,
) {
    enum class UpdateResult { SAVED, UNCHANGED, NEEDS_UNLOCK }

    private class NeedsUnlock : RuntimeException()

    private val store: DataStore<BlockerConfig> = DataStoreFactory.create(
        serializer = JsonSerializer(BlockerConfig.serializer(), BlockerConfig()),
        corruptionHandler = ReplaceFileCorruptionHandler { BlockerConfig() },
        scope = scope,
        produceFile = { File(File(context.filesDir, "config"), "blocker_config.json") },
    )

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val config: StateFlow<BlockerConfig> = store.data
        .onEach { _loaded.value = true }
        .stateIn(scope, SharingStarted.Eagerly, BlockerConfig())

    suspend fun current(): BlockerConfig = store.data.first()

    /** True if weakening changes are currently allowed without a challenge. */
    fun canWeaken(c: BlockerConfig = config.value): Boolean =
        !c.settings.protectChanges || c.isSetupMode || unlocks.isUnlocked(UnlockTarget.SETTINGS)

    /**
     * Applies [transform]. If the result weakens protection and changes are
     * protected, nothing is written and NEEDS_UNLOCK is returned.
     */
    suspend fun update(transform: (BlockerConfig) -> BlockerConfig): UpdateResult {
        var result = UpdateResult.UNCHANGED
        try {
            store.updateData { old ->
                val new = ConfigPolicy.sanitize(transform(old))
                if (new == old) return@updateData old
                if (ConfigPolicy.isWeakening(old, new) && !canWeaken(old)) throw NeedsUnlock()
                result = UpdateResult.SAVED
                new
            }
        } catch (e: NeedsUnlock) {
            return UpdateResult.NEEDS_UNLOCK
        }
        return result
    }
}
