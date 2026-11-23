package app.impulseblocker.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import app.impulseblocker.core.BlockerConfig
import app.impulseblocker.core.ConfigPolicy
import app.impulseblocker.core.ExportFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manual export / import through the Storage Access Framework. The user picks
 * the file location (local storage, a USB drive, or a cloud provider of their
 * choice); the app itself never uploads anything.
 */
class BackupManager(
    private val context: Context,
    private val configRepo: ConfigRepository,
    private val photoStore: PhotoStore,
) {
    data class ImportPreview(val config: BlockerConfig, val photoBytes: ByteArray?)

    suspend fun export(uri: Uri, includePhoto: Boolean) = withContext(Dispatchers.IO) {
        val photo = if (includePhoto) photoStore.readJpegBytes() else null
        val file = ExportFile(
            exportedAtEpochMs = System.currentTimeMillis(),
            config = configRepo.current(),
            photoJpegBase64 = photo?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
        )
        val text = ConfigPolicy.encodeExport(file)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) }
            ?: throw IllegalStateException("Could not open the selected file for writing")
    }

    suspend fun readImport(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not open the selected file")
        if (bytes.size > 20 * 1024 * 1024) throw IllegalArgumentException("The file is too large")
        val file = ConfigPolicy.decodeExport(bytes.decodeToString())
        val photo = file.photoJpegBase64?.let {
            try {
                Base64.decode(it, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        ImportPreview(file.config, photo)
    }

    /** Replaces the configuration. May return NEEDS_UNLOCK if it would weaken protection. */
    suspend fun applyImport(preview: ImportPreview, restorePhoto: Boolean): ConfigRepository.UpdateResult {
        val result = configRepo.update { preview.config }
        if (result != ConfigRepository.UpdateResult.NEEDS_UNLOCK && restorePhoto && preview.photoBytes != null) {
            photoStore.setFromJpegBytes(preview.photoBytes, preview.config.settings.includePhotoInDeviceBackup)
        }
        return result
    }
}
