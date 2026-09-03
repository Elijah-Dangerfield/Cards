package com.dangerfield.cards.libraries.cards

/**
 * A `major.minor.patch` version, parsed leniently enough to survive whatever the
 * stores hand back.
 *
 * Store metadata is not disciplined about this: the Play track can carry
 * `0.2.0`, App Store Connect can carry `0.2` or `0.2.0 (1026)`, and a hand-typed
 * value can carry anything. Missing components read as `0`, trailing junk is
 * ignored, and anything without a leading integer fails to parse rather than
 * guessing — a prompt driven by a misread version is worse than no prompt.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    /**
     * True when [other] is a new *feature* release relative to this one, i.e. the
     * major or minor moved. A patch-only difference is deliberately not a
     * feature bump: see [isWorthPromptingFrom].
     */
    fun isFeatureBumpOver(other: AppVersion): Boolean =
        major > other.major || (major == other.major && minor > other.minor)

    companion object {
        /**
         * Parses the leading `major[.minor[.patch]]` of [raw], or null when
         * there isn't one. Never throws.
         */
        fun parseOrNull(raw: String?): AppVersion? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val parts = trimmed
                .takeWhile { it.isDigit() || it == '.' }
                .split('.')
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            return AppVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
            )
        }
    }
}

/**
 * Whether a store version is worth interrupting someone's Home screen for.
 *
 * Three conditions, all required:
 *
 * 1. **It's actually newer.** A store reporting the same or an older version
 *    (a staged rollout the user is ahead of, a lagging cache) prompts nothing.
 * 2. **It's a feature bump.** A run of `0.2.1`, `0.2.2`, `0.2.3` stays silent;
 *    `0.3.0` earns one prompt. This is what stops the dialog from becoming
 *    background noise, without a permanent "never ask again" flag that would
 *    also hide the releases people genuinely want.
 * 3. **We haven't already asked about this version.** Keyed on the version we
 *    last prompted for, so someone who skips `0.3.0` and then `0.4.0` is asked
 *    twice — once per feature release — rather than on every cold start.
 *
 * Pure. [lastPrompted] is the persisted marker, null/blank when we've never
 * prompted.
 */
fun AppVersion.isWorthPromptingFrom(
    installed: AppVersion,
    lastPrompted: AppVersion?,
): Boolean {
    if (this <= installed) return false
    if (!isFeatureBumpOver(installed)) return false
    return lastPrompted == null || this > lastPrompted
}
