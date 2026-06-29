package com.dangerfield.cards.features.room.impl.session

import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.BotThought
import com.dangerfield.cards.libraries.cards.GameSpeed
import kotlin.math.abs

/**
 * Computes how long a bot should "think" before submitting an action.
 *
 * Three signals stack multiplicatively, then we clamp to a humane range:
 *
 *  1. **Personality** — aggressive bots act faster, tight bots take longer.
 *     Mike (aggression=0.9, tightness=0.2) snap-shoves; Steve (aggression=0.22,
 *     tightness=0.38) tanks. Bounded so even the snap-iest bot still has a
 *     readable pause.
 *
 *  2. **Decision complexity** — close calls take longer. When the bot's hand
 *     strength is near the pot odds the call is genuinely marginal, so the
 *     bot lingers; when one side dominates (clear value bet, clear fold) the
 *     decision snaps.
 *
 *  3. **User pace** — bots subtly mirror the human's tempo so the table
 *     feels conversational, not metronomic. Light influence only (25% weight)
 *     so a slow user doesn't grind play to a halt and a snap user doesn't
 *     make the table feel chaotic. If we have no samples yet, this factor is
 *     a no-op.
 *
 * All factors are bounded individually; final result clamps to [MIN_MS, MAX_MS].
 * Keep this function pure — easier to test, easier to reason about, no
 * surprise behavior creeping in.
 */
internal object BotTiming {

    /** Floor — anything faster reads as a bug. */
    const val MIN_THINK_MS: Long = 450L

    /** Ceiling — past this it feels like the app froze. */
    const val MAX_THINK_MS: Long = 2400L

    /** Median bot, median decision, no user pace data. */
    const val BASE_THINK_MS: Long = 800L

    fun thinkDelayMs(
        personality: BotPersonality,
        thought: BotThought,
        userPaceMs: Long?,
        speed: GameSpeed = GameSpeed.Normal,
    ): Long {
        // Tight bots slow down (deliberation = caution); aggressive bots
        // speed up (action = confidence). Each axis contributes ±0.2, so the
        // combined personality factor lives in roughly [0.6, 1.4].
        val personalityFactor = (
            1.0 +
                (personality.tightness - 0.5) * 0.4 +
                (0.5 - personality.aggression) * 0.4
            ).coerceIn(0.5, 1.5)

        // gap ~0 → call/fold is right on the equity line → tank.
        // gap ≥ 0.5 → one side dominates → snap.
        val gap = abs(thought.handStrength - thought.potOdds)
        val complexityFactor = (1.6 - gap * 2.2).coerceIn(0.5, 1.6)

        // Lightly normalize the user's pace against BASE_THINK_MS, blend at
        // 25%. So a user averaging 4s only pulls bots toward ~1.2× base, not
        // toward 4s; a snap-tapper at 200ms only pulls bots toward ~0.87×.
        val userBlend = if (userPaceMs != null) {
            val normalized = (userPaceMs.toDouble() / BASE_THINK_MS).coerceIn(0.5, 2.0)
            0.75 + 0.25 * normalized
        } else 1.0

        // The chosen Game speed scales the whole thing AFTER clamping. Fast can
        // dip below the normal MIN floor — that's the explicit user preference,
        // so we honor it down to the hard fast floor rather than re-clamping.
        val base = (BASE_THINK_MS * personalityFactor * complexityFactor * userBlend)
            .toLong()
            .coerceIn(MIN_THINK_MS, MAX_THINK_MS)
        return (base * speed.botThinkScale).toLong().coerceAtLeast(MIN_THINK_FAST_MS)
    }

    /** Hard floor even at Fast speed — anything quicker reads as a glitch. */
    private const val MIN_THINK_FAST_MS: Long = 250L
}
