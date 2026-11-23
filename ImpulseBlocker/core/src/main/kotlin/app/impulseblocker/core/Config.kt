package app.impulseblocker.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UnlockSettings(
    val waitEnabled: Boolean = true,
    val waitMinutes: Int = 15,
    val mathEnabled: Boolean = true,
    val questionCount: Int = 10,
    val difficulty: Difficulty = Difficulty.HARD,
    val unlockMinutes: Int = 30,
    /** Removing items or weakening settings requires completing the challenge. */
    val protectChanges: Boolean = true,
    /** Block well-known DNS-over-HTTPS hostnames so browsers cannot bypass the filter. */
    val blockEncryptedDns: Boolean = true,
    /** Store the personal photo where Android Auto Backup includes it. */
    val includePhotoInDeviceBackup: Boolean = false,
) {
    companion object {
        val WAIT_RANGE = 1..180
        val QUESTION_RANGE = 1..50
        val UNLOCK_RANGE = 1..240
    }
}

@Serializable
data class BlockerConfig(
    val version: Int = 1,
    /** Package names, never display names. */
    val blockedApps: List<String> = emptyList(),
    /** Normalized domain rules (each also covers its subdomains). */
    val blockedDomains: List<String> = emptyList(),
    val appBlockingEnabled: Boolean = true,
    val webBlockingEnabled: Boolean = false,
    val settings: UnlockSettings = UnlockSettings(),
) {
    /** Nothing is blocked yet: the first setup can be done without challenges. */
    val isSetupMode: Boolean get() = blockedApps.isEmpty() && blockedDomains.isEmpty()
}

/** File written by manual export (Storage Access Framework). */
@Serializable
data class ExportFile(
    val format: String = FORMAT,
    val formatVersion: Int = 1,
    val exportedAtEpochMs: Long = 0,
    val config: BlockerConfig,
    /** Present only if the user explicitly chose to include the photo. */
    val photoJpegBase64: String? = null,
) {
    companion object {
        const val FORMAT = "impulse-blocker-config"
    }
}

object ConfigJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }
}

object ConfigPolicy {

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isValidPackageName(p: String) = p.length <= 255 && PACKAGE_NAME.matches(p)

    /**
     * True if going from [old] to [new] makes the blocker weaker in any way, so
     * that the change must be protected by the unlock challenge.
     */
    fun isWeakening(old: BlockerConfig, new: BlockerConfig): Boolean {
        if (!new.blockedApps.toSet().containsAll(old.blockedApps)) return true
        if (!new.blockedDomains.toSet().containsAll(old.blockedDomains)) return true
        if (old.appBlockingEnabled && !new.appBlockingEnabled) return true
        if (old.webBlockingEnabled && !new.webBlockingEnabled) return true
        val o = old.settings
        val n = new.settings
        return (o.waitEnabled && !n.waitEnabled) ||
            (n.waitEnabled && n.waitMinutes < o.waitMinutes) ||
            (o.mathEnabled && !n.mathEnabled) ||
            (n.mathEnabled && n.questionCount < o.questionCount) ||
            (n.mathEnabled && n.difficulty.ordinal < o.difficulty.ordinal) ||
            n.unlockMinutes > o.unlockMinutes ||
            (o.protectChanges && !n.protectChanges) ||
            (o.blockEncryptedDns && !n.blockEncryptedDns)
    }

    /** Cleans an imported/restored configuration so it can never contain invalid values. */
    fun sanitize(c: BlockerConfig): BlockerConfig {
        val apps = c.blockedApps.map { it.trim() }.filter { isValidPackageName(it) }.distinct()
        val domains = c.blockedDomains.mapNotNull {
            (DomainRules.normalize(it) as? DomainRules.Result.Ok)?.domain
        }.distinct()
        val s = c.settings
        return c.copy(
            version = 1,
            blockedApps = apps,
            blockedDomains = domains,
            settings = s.copy(
                waitMinutes = s.waitMinutes.coerceIn(UnlockSettings.WAIT_RANGE),
                questionCount = s.questionCount.coerceIn(UnlockSettings.QUESTION_RANGE),
                unlockMinutes = s.unlockMinutes.coerceIn(UnlockSettings.UNLOCK_RANGE),
            ),
        )
    }

    fun encodeExport(file: ExportFile): String =
        ConfigJson.json.encodeToString(ExportFile.serializer(), file)

    /** Parses an export file; throws IllegalArgumentException with a readable message. */
    fun decodeExport(text: String): ExportFile {
        val file = try {
            ConfigJson.json.decodeFromString(ExportFile.serializer(), text)
        } catch (e: Exception) {
            throw IllegalArgumentException("This is not a valid Impulse Blocker backup file.", e)
        }
        require(file.format == ExportFile.FORMAT) { "This is not an Impulse Blocker backup file." }
        require(file.formatVersion <= 1) { "This backup was made by a newer version of the app." }
        return file.copy(config = sanitize(file.config))
    }
}
