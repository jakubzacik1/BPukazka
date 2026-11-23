package app.impulseblocker.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.impulseblocker.core.ConfigJson
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** DataStore serializer that stores a @Serializable class as JSON. */
class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return try {
            ConfigJson.json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot read stored data", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Cannot read stored data", e)
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(ConfigJson.json.encodeToString(serializer, t).encodeToByteArray())
    }
}
