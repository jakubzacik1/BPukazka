package app.impulseblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    private val base = BlockerConfig(
        blockedApps = listOf("com.wolt.android", "com.ubercab.eats"),
        blockedDomains = listOf("wolt.com"),
        webBlockingEnabled = true,
    )

    @Test fun weakeningDetection() {
        assertFalse(ConfigPolicy.isWeakening(base, base))
        assertFalse(ConfigPolicy.isWeakening(base, base.copy(blockedApps = base.blockedApps + "com.x.y")))
        assertTrue(ConfigPolicy.isWeakening(base, base.copy(blockedApps = listOf("com.wolt.android"))))
        assertTrue(ConfigPolicy.isWeakening(base, base.copy(blockedDomains = emptyList())))
        assertTrue(ConfigPolicy.isWeakening(base, base.copy(webBlockingEnabled = false)))
        assertTrue(ConfigPolicy.isWeakening(base, base.copy(settings = base.settings.copy(waitMinutes = 5))))
        assertFalse(ConfigPolicy.isWeakening(base, base.copy(settings = base.settings.copy(waitMinutes = 60))))
        assertTrue(ConfigPolicy.isWeakening(base, base.copy(settings = base.settings.copy(unlockMinutes = 60))))
        assertFalse(ConfigPolicy.isWeakening(base, base.copy(settings = base.settings.copy(unlockMinutes = 10))))
        assertTrue(ConfigPolicy.isWeakening(base, base.copy(settings = base.settings.copy(difficulty = Difficulty.EASY))))
        assertTrue(ConfigPolicy.isWeakening(base, base.copy(settings = base.settings.copy(mathEnabled = false))))
        assertTrue(ConfigPolicy.isWeakening(base, base.copy(settings = base.settings.copy(protectChanges = false))))
    }

    @Test fun exportImportRoundTrip() {
        val file = ExportFile(exportedAtEpochMs = 123, config = base, photoJpegBase64 = null)
        val text = ConfigPolicy.encodeExport(file)
        val back = ConfigPolicy.decodeExport(text)
        assertEquals(base, back.config)
        assertNull(back.photoJpegBase64)
    }

    @Test fun importSanitizesValues() {
        val text = """
            {"format":"impulse-blocker-config","formatVersion":1,
             "config":{"blockedApps":["com.wolt.android","not a package","com.wolt.android"],
                       "blockedDomains":["https://www.Wolt.com/x","???"],
                       "settings":{"waitMinutes":0,"questionCount":999,"unlockMinutes":100000,"unknown":1}},
             "extra":true}
        """.trimIndent()
        val c = ConfigPolicy.decodeExport(text).config
        assertEquals(listOf("com.wolt.android"), c.blockedApps)
        assertEquals(listOf("wolt.com"), c.blockedDomains)
        assertEquals(1, c.settings.waitMinutes)
        assertEquals(50, c.settings.questionCount)
        assertEquals(240, c.settings.unlockMinutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsForeignJson() {
        ConfigPolicy.decodeExport("""{"format":"something-else","config":{}}""")
    }

    @Test fun defaultsAreStrict() {
        val s = UnlockSettings()
        assertEquals(15, s.waitMinutes)
        assertEquals(10, s.questionCount)
        assertEquals(30, s.unlockMinutes)
        assertTrue(s.waitEnabled && s.mathEnabled && s.protectChanges)
    }
}
