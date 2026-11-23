package app.impulseblocker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The personal blocking-screen photo. A private, downscaled JPEG copy is kept in
 * app storage and never leaves the device unless the user explicitly opts in:
 *  - default location: noBackupFilesDir/photo/ (excluded from Android backup);
 *  - if "include photo in device backup" is on: filesDir/photo_backup/.
 */
class PhotoStore(private val context: Context) {

    private val privateFile get() = File(File(context.noBackupFilesDir, "photo"), FILE)
    private val backupFile get() = File(File(context.filesDir, "photo_backup"), FILE)

    private val _version = MutableStateFlow(0L)
    /** Changes whenever the photo changes, so the UI can reload it. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun currentFile(): File? = listOf(backupFile, privateFile).firstOrNull { it.exists() }

    fun hasPhoto(): Boolean = currentFile() != null

    suspend fun setFromUri(uri: Uri, includeInBackup: Boolean) = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = MAX_EDGE.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        writeJpeg(bitmap, includeInBackup)
    }

    suspend fun setFromJpegBytes(bytes: ByteArray, includeInBackup: Boolean) = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("The photo in the backup file is damaged")
        writeJpeg(bitmap, includeInBackup)
    }

    private fun writeJpeg(bitmap: Bitmap, includeInBackup: Boolean) {
        val target = if (includeInBackup) backupFile else privateFile
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$FILE.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (!tmp.renameTo(target)) throw IllegalStateException("Could not save photo")
        (if (includeInBackup) privateFile else backupFile).delete()
        _version.value++
    }

    suspend fun readJpegBytes(): ByteArray? = withContext(Dispatchers.IO) { currentFile()?.readBytes() }

    suspend fun remove() = withContext(Dispatchers.IO) {
        privateFile.delete()
        backupFile.delete()
        _version.value++
    }

    /** Moves the photo into or out of the backed-up directory. */
    suspend fun relocate(includeInBackup: Boolean) = withContext(Dispatchers.IO) {
        val from = if (includeInBackup) privateFile else backupFile
        val to = if (includeInBackup) backupFile else privateFile
        if (from.exists()) {
            to.parentFile?.mkdirs()
            from.copyTo(to, overwrite = true)
            from.delete()
            _version.value++
        }
    }

    suspend fun loadBitmap(maxEdge: Int = 1400): Bitmap? = withContext(Dispatchers.IO) {
        val file = currentFile() ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val FILE = "photo.jpg"
        private const val MAX_EDGE = 1600
    }
}
