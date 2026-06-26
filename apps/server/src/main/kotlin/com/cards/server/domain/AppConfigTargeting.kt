package com.dangerfield.cards.server.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * The predicate attached to a targeting rule, stored as `conditions_jsonb`.
 *
 * Every field is optional; an unset field is "don't care". A rule matches when
 * **all** set conditions pass. An all-null predicate (`{}`) is an unconditional
 * override at its priority — handy for a temporary global value above the base.
 *
 * Axes:
 *  - [platforms] — `"android"` / `"ios"` / `"other"` (lowercased [ClientContext.Platform]).
 *  - [minVersionCode] / [maxVersionCode] — inclusive build-number range; a
 *    client that doesn't send a build number fails a set bound.
 *  - [minAppVersion] / [maxAppVersion] — **semantic-version** bounds (e.g.
 *    `"1.0.1"`) compared by SemVer precedence against the client's `appVersion`.
 *    The `*Inclusive` flags pick `≥`/`>` and `≤`/`<`, so "version `> 1.0.1`" is
 *    `minAppVersion = "1.0.1", minAppVersionInclusive = false`, and an exact
 *    match is `min == max` both inclusive. A client that doesn't send an app
 *    version fails a set bound (same fail-closed rule as the version code).
 *  - [countries] — ISO 3166-1 alpha-2, uppercase (matches `X-Country-Code`).
 *  - [locales] — primary language subtags, lowercase (`"en"`, `"es"`); matches
 *    if any of the client's preferred locales shares a primary subtag.
 *  - [userAllow] / [userDeny] — user-id (UUID string) allow / deny lists.
 *    Deny is a hard veto; allow requires the caller to be a known user in the set.
 *  - [rolloutPercent] — 0–100 staged rollout. Deterministic per
 *    (bucket-key, flag): the same user/install always lands in the same bucket,
 *    so ramping the percentage only ever *adds* users, never reshuffles them.
 */
@Serializable
data class RuleConditions(
    val platforms: Set<String>? = null,
    val minVersionCode: Int? = null,
    val maxVersionCode: Int? = null,
    val minAppVersion: String? = null,
    val minAppVersionInclusive: Boolean = true,
    val maxAppVersion: String? = null,
    val maxAppVersionInclusive: Boolean = true,
    val countries: Set<String>? = null,
    val locales: Set<String>? = null,
    val userAllow: Set<String>? = null,
    val userDeny: Set<String>? = null,
    val rolloutPercent: Int? = null,
)

/**
 * A single resolved targeting rule for a flag [flagPath]. [value] is what the
 * flag resolves to when [conditions] match; rules are ordered by [priority]
 * (ascending, first match wins). Disabled rules are skipped without being
 * deleted, so an operator can park a rule and re-enable it later.
 */
data class TargetingRule(
    val id: UUID,
    val flagPath: String,
    val priority: Int,
    val value: JsonElement,
    val conditions: RuleConditions,
    val enabled: Boolean,
    val description: String?,
)
