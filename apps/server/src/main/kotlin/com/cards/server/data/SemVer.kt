package com.dangerfield.cards.server.data

/**
 * Minimal semantic-version comparison for targeting rules (`> 1.0.1` etc.).
 *
 * We only need precedence ordering, so this implements the SemVer 2.0.0
 * precedence rules and nothing else (no constraint grammar, no ranges — the
 * rule model expresses ranges with a min/max pair):
 *
 *  - Compare `major`, `minor`, `patch` numerically.
 *  - A version *with* a pre-release tag has **lower** precedence than the same
 *    version without one (`1.0.0-rc1 < 1.0.0`).
 *  - Pre-release identifiers compare left-to-right: numeric identifiers
 *    numerically, alphanumeric lexically; numeric ranks below alphanumeric; a
 *    longer identifier list wins when all shared identifiers are equal.
 *
 * Build metadata (`+abc`) is ignored, per spec. Parsing is lenient: a leading
 * `v`, missing minor/patch, and non-numeric core segments degrade to `0` rather
 * than throwing, so a malformed `appVersion` can never crash resolution — it
 * just sorts low.
 */
internal object SemVer {

    fun compare(a: String, b: String): Int {
        val (aCore, aPre) = parse(a)
        val (bCore, bPre) = parse(b)
        for (i in 0..2) {
            val c = aCore[i].compareTo(bCore[i])
            if (c != 0) return c
        }
        return comparePreRelease(aPre, bPre)
    }

    /** Core triple (major, minor, patch) + the pre-release identifiers (may be empty). */
    private fun parse(raw: String): Pair<IntArray, List<String>> {
        val noBuild = raw.trim().removePrefix("v").substringBefore('+')
        val core = noBuild.substringBefore('-')
        val pre = noBuild.substringAfter('-', missingDelimiterValue = "")
        val nums = core.split('.')
        val triple = IntArray(3) { idx -> nums.getOrNull(idx)?.trim()?.toIntOrNull() ?: 0 }
        val preIds = if (pre.isEmpty()) emptyList() else pre.split('.').filter { it.isNotEmpty() }
        return triple to preIds
    }

    private fun comparePreRelease(a: List<String>, b: List<String>): Int {
        // No pre-release outranks any pre-release (1.0.0 > 1.0.0-rc1).
        if (a.isEmpty() && b.isEmpty()) return 0
        if (a.isEmpty()) return 1
        if (b.isEmpty()) return -1
        val shared = minOf(a.size, b.size)
        for (i in 0 until shared) {
            val c = compareIdentifier(a[i], b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    private fun compareIdentifier(a: String, b: String): Int {
        val an = a.toIntOrNull()
        val bn = b.toIntOrNull()
        return when {
            an != null && bn != null -> an.compareTo(bn)
            an != null -> -1 // numeric identifiers have lower precedence than alphanumeric
            bn != null -> 1
            else -> a.compareTo(b)
        }
    }
}
