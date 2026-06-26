package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.RuleConditions
import com.dangerfield.cards.server.domain.TargetingRule
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.http.ClientContext
import kotlinx.serialization.json.JsonElement

/**
 * Resolves a flag's value for one caller by evaluating its targeting rules.
 *
 * Pure + deterministic — no DB, no clock, no randomness — so it unit-tests
 * directly and a given (rules, caller) always resolves the same way. The DB
 * source ([PostgresAppConfigSource]) loads the rules; this just decides.
 *
 * Algorithm: take enabled rules in ascending priority, return the value of the
 * first whose [RuleConditions] match, else the base value (which may be null,
 * meaning "fall through to the client default").
 */
class AppConfigTargetingEngine {

    fun resolve(
        rules: List<TargetingRule>,
        base: JsonElement?,
        context: ClientContext,
        userId: UserId?,
        flagPath: String,
    ): JsonElement? = firstMatchingRule(rules, context, userId, flagPath)?.value ?: base

    /**
     * The enabled rule that wins for this caller (lowest priority whose
     * conditions match), or null if none do. Exposed so the admin "resolve"
     * preview can show *which* rule decided a value, not just the value.
     */
    fun firstMatchingRule(
        rules: List<TargetingRule>,
        context: ClientContext,
        userId: UserId?,
        flagPath: String,
    ): TargetingRule? = rules
        .asSequence()
        .filter { it.enabled }
        .sortedBy { it.priority }
        .firstOrNull { matches(it.conditions, context, userId, flagPath) }

    private fun matches(
        conditions: RuleConditions,
        context: ClientContext,
        userId: UserId?,
        flagPath: String,
    ): Boolean {
        conditions.platforms?.let { allowed ->
            if (context.platform.wireName() !in allowed) return false
        }
        conditions.minVersionCode?.let { min ->
            val build = context.buildNumber ?: return false
            if (build < min) return false
        }
        conditions.maxVersionCode?.let { max ->
            val build = context.buildNumber ?: return false
            if (build > max) return false
        }
        conditions.minAppVersion?.let { min ->
            val version = context.appVersion ?: return false
            val cmp = SemVer.compare(version, min)
            if (if (conditions.minAppVersionInclusive) cmp < 0 else cmp <= 0) return false
        }
        conditions.maxAppVersion?.let { max ->
            val version = context.appVersion ?: return false
            val cmp = SemVer.compare(version, max)
            if (if (conditions.maxAppVersionInclusive) cmp > 0 else cmp >= 0) return false
        }
        conditions.countries?.let { allowed ->
            val country = context.countryCode ?: return false
            if (country.uppercase() !in allowed.map { it.uppercase() }) return false
        }
        conditions.locales?.let { allowed ->
            val wanted = allowed.map { it.primarySubtag() }.toSet()
            val have = context.preferredLocales.map { it.primarySubtag() }
            if (have.none { it in wanted }) return false
        }

        val uid = userId?.value?.toString()
        if (uid != null && conditions.userDeny?.contains(uid) == true) return false
        conditions.userAllow?.let { allowed ->
            if (uid == null || uid !in allowed) return false
        }

        conditions.rolloutPercent?.let { percent ->
            val bucket = rolloutBucket(bucketKey(userId, context), flagPath)
            if (bucket >= percent.coerceIn(0, 100)) return false
        }
        return true
    }

    /**
     * Stable rollout identity: the signed-in user when we have one, else the
     * install id (so an anonymous, pre-auth client still buckets consistently),
     * else a constant (everyone shares one bucket — degrades to "global %").
     */
    private fun bucketKey(userId: UserId?, context: ClientContext): String =
        userId?.value?.toString() ?: context.installId ?: ANON_BUCKET_KEY

    /** 0–99 bucket, deterministic per (key, flag). */
    private fun rolloutBucket(key: String, flagPath: String): Int =
        (fnv1a32("$key:$flagPath").toLong() and 0xFFFFFFFFL).mod(100L).toInt()

    private fun String.primarySubtag(): String = substringBefore('-').lowercase()

    private fun ClientContext.Platform.wireName(): String = when (this) {
        ClientContext.Platform.Android -> "android"
        ClientContext.Platform.iOS -> "ios"
        ClientContext.Platform.Other -> "other"
    }

    /**
     * 32-bit FNV-1a. Cheap, stable, and platform-independent — we need a hash
     * whose buckets don't shift between server versions (unlike `String.hashCode`,
     * which carries no such guarantee), so a user can't slip out of a rollout
     * they were already in.
     */
    private fun fnv1a32(input: String): Int {
        var hash = -0x7ee3623b // 2166136261 (FNV offset basis) as a signed Int
        for (byte in input.encodeToByteArray()) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 0x01000193 // 16777619 (FNV prime)
        }
        return hash
    }

    private companion object {
        const val ANON_BUCKET_KEY = "anon"
    }
}
