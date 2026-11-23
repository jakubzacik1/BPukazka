package app.impulseblocker.core

import kotlinx.serialization.Serializable

/**
 * A point on the monotonic clock: Android's `SystemClock.elapsedRealtime()`
 * (counts deep sleep, cannot be changed by the user) qualified by the boot count,
 * because elapsed time restarts at zero on every boot. Changing the wall clock
 * has no effect on anything measured this way.
 */
@Serializable
data class MonoInstant(val bootCount: Int, val elapsedMs: Long)

fun interface MonotonicClock {
    fun now(): MonoInstant
}

@Serializable
data class Deadline(
    val bootCount: Int,
    val endElapsedMs: Long,
    /** Set when the device shuts down cleanly: remaining time to resume after the next boot. */
    val pausedRemainingMs: Long? = null,
) {
    /** Remaining milliseconds, or null if the deadline belongs to another boot. */
    fun remainingMs(now: MonoInstant): Long? {
        if (pausedRemainingMs != null) return pausedRemainingMs
        if (now.bootCount != bootCount) return null
        return (endElapsedMs - now.elapsedMs).coerceAtLeast(0)
    }

    companion object {
        fun after(now: MonoInstant, durationMs: Long) =
            Deadline(now.bootCount, now.elapsedMs + durationMs)
    }
}

@Serializable
enum class TargetType { APP, DOMAIN, SETTINGS }

/** What a challenge unlocks: one app package, one domain rule, or settings editing. */
@Serializable
data class UnlockTarget(val type: TargetType, val id: String) {
    companion object {
        fun app(pkg: String) = UnlockTarget(TargetType.APP, pkg)
        fun domain(rule: String) = UnlockTarget(TargetType.DOMAIN, rule)
        val SETTINGS = UnlockTarget(TargetType.SETTINGS, "settings")
    }
}

@Serializable
data class TemporaryUnlock(val target: UnlockTarget, val deadline: Deadline, val durationMs: Long)

@Serializable
enum class ChallengeStage { WAITING, MATH, CONFIRM }

@Serializable
data class ChallengeState(
    val target: UnlockTarget,
    val stage: ChallengeStage,
    val waitDeadline: Deadline? = null,
    val waitDurationMs: Long = 0,
    val mathRequired: Boolean = true,
    val questionCount: Int = 10,
    val difficulty: Difficulty = Difficulty.HARD,
    val unlockDurationMs: Long = 30 * 60_000L,
)

@Serializable
data class RuntimeState(
    val unlocks: List<TemporaryUnlock> = emptyList(),
    val challenge: ChallengeState? = null,
)

/**
 * Pure state transitions for the unlock flow. Everything is persisted as
 * [RuntimeState]; the UI only renders it, so closing the UI cannot reset or
 * skip any step.
 */
object UnlockLogic {

    const val SETTINGS_EDIT_WINDOW_MS = 10 * 60_000L

    /**
     * Brings deadlines from a previous boot into the current one:
     *  - cleanly paused at shutdown -> resume with the remaining time;
     *  - otherwise (crash / battery pull / unknown) -> temporary unlocks end and
     *    a running waiting period starts over. Failing closed means a reboot can
     *    never be used to skip the wait or extend access.
     */
    fun reconcile(state: RuntimeState, now: MonoInstant): RuntimeState {
        val unlocks = state.unlocks.mapNotNull { u ->
            val d = u.deadline
            when {
                d.pausedRemainingMs != null -> u.copy(deadline = Deadline.after(now, d.pausedRemainingMs))
                d.bootCount != now.bootCount -> null
                d.endElapsedMs <= now.elapsedMs -> null
                else -> u
            }
        }
        val ch = state.challenge?.let { c ->
            val d = c.waitDeadline ?: return@let c
            when {
                d.pausedRemainingMs != null -> c.copy(waitDeadline = Deadline.after(now, d.pausedRemainingMs))
                d.bootCount != now.bootCount -> c.copy(waitDeadline = Deadline.after(now, c.waitDurationMs))
                else -> c
            }
        }
        return RuntimeState(unlocks, ch)
    }

    /** Called on ACTION_SHUTDOWN: freezes the remaining time of every deadline. */
    fun pauseForShutdown(state: RuntimeState, now: MonoInstant): RuntimeState {
        fun pause(d: Deadline): Deadline =
            if (d.pausedRemainingMs != null) d
            else Deadline(d.bootCount, d.endElapsedMs, d.remainingMs(now) ?: 0L)
        return RuntimeState(
            unlocks = state.unlocks.map { it.copy(deadline = pause(it.deadline)) },
            challenge = state.challenge?.let { c -> c.copy(waitDeadline = c.waitDeadline?.let(::pause)) },
        )
    }

    fun activeUnlock(state: RuntimeState, target: UnlockTarget, now: MonoInstant): TemporaryUnlock? =
        state.unlocks.firstOrNull { it.target == target && (it.deadline.remainingMs(now) ?: 0L) > 0L }

    fun isUnlocked(state: RuntimeState, target: UnlockTarget, now: MonoInstant): Boolean =
        activeUnlock(state, target, now) != null

    fun remainingUnlockMs(state: RuntimeState, target: UnlockTarget, now: MonoInstant): Long =
        activeUnlock(state, target, now)?.deadline?.remainingMs(now) ?: 0L

    fun pruneExpired(state: RuntimeState, now: MonoInstant): RuntimeState =
        state.copy(unlocks = state.unlocks.filter { (it.deadline.remainingMs(now) ?: 0L) > 0L })

    /** Next moment (ms from now) at which some unlock expires, or null. */
    fun nextExpiryInMs(state: RuntimeState, now: MonoInstant): Long? =
        state.unlocks.mapNotNull { it.deadline.remainingMs(now) }.filter { it > 0 }.minOrNull()

    fun startChallenge(
        state: RuntimeState,
        target: UnlockTarget,
        settings: UnlockSettings,
        now: MonoInstant,
    ): RuntimeState {
        val waitMs = if (settings.waitEnabled) settings.waitMinutes * 60_000L else 0L
        val unlockMs = if (target.type == TargetType.SETTINGS) SETTINGS_EDIT_WINDOW_MS
        else settings.unlockMinutes * 60_000L
        val challenge = ChallengeState(
            target = target,
            stage = when {
                waitMs > 0 -> ChallengeStage.WAITING
                settings.mathEnabled -> ChallengeStage.MATH
                else -> ChallengeStage.CONFIRM
            },
            waitDeadline = if (waitMs > 0) Deadline.after(now, waitMs) else null,
            waitDurationMs = waitMs,
            mathRequired = settings.mathEnabled,
            questionCount = settings.questionCount,
            difficulty = settings.difficulty,
            unlockDurationMs = unlockMs,
        )
        return state.copy(challenge = challenge)
    }

    /** The stage the user is really in, taking elapsed waiting time into account. */
    fun effectiveStage(c: ChallengeState, now: MonoInstant): ChallengeStage {
        if (c.stage != ChallengeStage.WAITING) return c.stage
        val remaining = c.waitDeadline?.remainingMs(now) ?: return ChallengeStage.WAITING
        if (remaining > 0) return ChallengeStage.WAITING
        return if (c.mathRequired) ChallengeStage.MATH else ChallengeStage.CONFIRM
    }

    fun waitRemainingMs(c: ChallengeState, now: MonoInstant): Long =
        if (c.stage != ChallengeStage.WAITING) 0L else c.waitDeadline?.remainingMs(now) ?: c.waitDurationMs

    /** Marks the math step as passed. Ignored unless the user is actually at that step. */
    fun completeMath(state: RuntimeState, now: MonoInstant): RuntimeState {
        val c = state.challenge ?: return state
        if (effectiveStage(c, now) != ChallengeStage.MATH) return state
        return state.copy(challenge = c.copy(stage = ChallengeStage.CONFIRM))
    }

    /** Final confirmation: grants access to exactly one target. */
    fun confirm(state: RuntimeState, now: MonoInstant): RuntimeState {
        val c = state.challenge ?: return state
        if (effectiveStage(c, now) != ChallengeStage.CONFIRM) return state
        val unlock = TemporaryUnlock(c.target, Deadline.after(now, c.unlockDurationMs), c.unlockDurationMs)
        val others = pruneExpired(state, now).unlocks.filter { it.target != c.target }
        return RuntimeState(unlocks = others + unlock, challenge = null)
    }

    fun cancelChallenge(state: RuntimeState): RuntimeState = state.copy(challenge = null)

    /** Ends a temporary unlock early (e.g. "Re-block now"). */
    fun revoke(state: RuntimeState, target: UnlockTarget): RuntimeState =
        state.copy(unlocks = state.unlocks.filter { it.target != target })
}
