package app.impulseblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockLogicTest {

    private val settings = UnlockSettings() // 15 min wait, math on, 30 min unlock
    private val wolt = UnlockTarget.app("com.wolt.android")
    private val bolt = UnlockTarget.app("ee.mtakso.client")
    private fun t(ms: Long, boot: Int = 1) = MonoInstant(boot, ms)
    private val min = 60_000L

    @Test fun fullFlowUnlocksOnlyTarget() {
        var s = UnlockLogic.startChallenge(RuntimeState(), wolt, settings, t(0))
        assertEquals(ChallengeStage.WAITING, UnlockLogic.effectiveStage(s.challenge!!, t(14 * min)))
        // Math cannot be completed while waiting
        assertEquals(s, UnlockLogic.completeMath(s, t(14 * min)))
        // Confirm cannot be skipped
        assertEquals(s, UnlockLogic.confirm(s, t(16 * min)))
        assertEquals(ChallengeStage.MATH, UnlockLogic.effectiveStage(s.challenge!!, t(15 * min)))
        s = UnlockLogic.completeMath(s, t(16 * min))
        assertEquals(ChallengeStage.CONFIRM, s.challenge!!.stage)
        s = UnlockLogic.confirm(s, t(17 * min))
        assertNull(s.challenge)
        assertTrue(UnlockLogic.isUnlocked(s, wolt, t(17 * min)))
        assertFalse(UnlockLogic.isUnlocked(s, bolt, t(17 * min)))
        assertFalse(UnlockLogic.isUnlocked(s, UnlockTarget.domain("wolt.com"), t(17 * min)))
        assertEquals(30 * min, UnlockLogic.remainingUnlockMs(s, wolt, t(17 * min)))
        // Expires after 30 minutes
        assertTrue(UnlockLogic.isUnlocked(s, wolt, t(47 * min - 1)))
        assertFalse(UnlockLogic.isUnlocked(s, wolt, t(47 * min)))
        assertTrue(UnlockLogic.pruneExpired(s, t(47 * min)).unlocks.isEmpty())
    }

    @Test fun disabledStepsAreSkipped() {
        val noWait = settings.copy(waitEnabled = false)
        assertEquals(ChallengeStage.MATH, UnlockLogic.startChallenge(RuntimeState(), wolt, noWait, t(0)).challenge!!.stage)
        val none = settings.copy(waitEnabled = false, mathEnabled = false)
        val s = UnlockLogic.startChallenge(RuntimeState(), wolt, none, t(0))
        assertEquals(ChallengeStage.CONFIRM, s.challenge!!.stage)
        val noMathWaited = UnlockLogic.startChallenge(RuntimeState(), wolt, settings.copy(mathEnabled = false), t(0))
        assertEquals(ChallengeStage.CONFIRM, UnlockLogic.effectiveStage(noMathWaited.challenge!!, t(15 * min)))
    }

    @Test fun cleanShutdownResumesRemainingTime() {
        var s = UnlockLogic.startChallenge(RuntimeState(), wolt, settings, t(1000 * min))
        s = UnlockLogic.pauseForShutdown(s, t(1010 * min)) // 5 min left
        s = UnlockLogic.reconcile(s, t(2 * min, boot = 2))
        assertEquals(5 * min, UnlockLogic.waitRemainingMs(s.challenge!!, t(2 * min, boot = 2)))
    }

    @Test fun uncleanRebootRestartsWaitAndEndsUnlocks() {
        var s = UnlockLogic.startChallenge(RuntimeState(), wolt, settings, t(1000 * min))
        s = s.copy(unlocks = listOf(TemporaryUnlock(bolt, Deadline.after(t(1000 * min), 30 * min), 30 * min)))
        s = UnlockLogic.reconcile(s, t(1 * min, boot = 2))
        assertTrue(s.unlocks.isEmpty())
        assertEquals(15 * min, UnlockLogic.waitRemainingMs(s.challenge!!, t(1 * min, boot = 2)))
        // Idempotent within the same boot
        assertEquals(s, UnlockLogic.reconcile(s, t(3 * min, boot = 2)))
    }

    @Test fun secondTargetStaysBlockedWhileFirstUnlocked() {
        val fast = settings.copy(waitEnabled = false, mathEnabled = false)
        var s = UnlockLogic.startChallenge(RuntimeState(), wolt, fast, t(0))
        s = UnlockLogic.confirm(s, t(0))
        assertTrue(UnlockLogic.isUnlocked(s, wolt, t(1)))
        assertFalse(UnlockLogic.isUnlocked(s, bolt, t(1)))
        s = UnlockLogic.startChallenge(s, bolt, fast, t(10 * min))
        s = UnlockLogic.confirm(s, t(10 * min))
        assertEquals(20 * min, UnlockLogic.remainingUnlockMs(s, wolt, t(10 * min)))
        assertEquals(30 * min, UnlockLogic.remainingUnlockMs(s, bolt, t(10 * min)))
        assertEquals(20 * min, UnlockLogic.nextExpiryInMs(s, t(10 * min)))
    }

    @Test fun settingsTargetUsesEditWindow() {
        val fast = settings.copy(waitEnabled = false, mathEnabled = false)
        var s = UnlockLogic.startChallenge(RuntimeState(), UnlockTarget.SETTINGS, fast, t(0))
        s = UnlockLogic.confirm(s, t(0))
        assertEquals(UnlockLogic.SETTINGS_EDIT_WINDOW_MS, UnlockLogic.remainingUnlockMs(s, UnlockTarget.SETTINGS, t(0)))
    }
}
