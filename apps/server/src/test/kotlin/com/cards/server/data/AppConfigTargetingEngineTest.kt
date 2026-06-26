package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.RuleConditions
import com.dangerfield.cards.server.domain.TargetingRule
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.http.ClientContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests for the targeting engine — no DB, no clock. Cover the match axes,
 * priority/first-match-wins ordering, the deny veto, and the determinism +
 * monotonicity properties rollout bucketing must hold.
 */
class AppConfigTargetingEngineTest {

    private val engine = AppConfigTargetingEngine()
    private val base = JsonPrimitive("base")

    private fun ctx(
        platform: ClientContext.Platform = ClientContext.Platform.Android,
        buildNumber: Int? = 100,
        appVersion: String? = "1.0.0",
        locales: List<String> = listOf("en-US"),
        country: String? = "US",
        installId: String? = "install-1",
    ) = ClientContext(
        platform = platform,
        appVersion = appVersion,
        buildNumber = buildNumber,
        preferredLocales = locales,
        countryCode = country,
        installId = installId,
    )

    private fun rule(
        conditions: RuleConditions,
        value: JsonElement = JsonPrimitive("on"),
        priority: Int = 0,
        enabled: Boolean = true,
    ) = TargetingRule(
        id = UUID.randomUUID(),
        flagPath = "feature.flag",
        priority = priority,
        value = value,
        conditions = conditions,
        enabled = enabled,
        description = null,
    )

    private fun resolve(
        rules: List<TargetingRule>,
        context: ClientContext = ctx(),
        userId: UserId? = null,
    ) = engine.resolve(rules, base, context, userId, "feature.flag")

    @Test
    fun noRules_returnsBase() {
        assertEquals(base, resolve(emptyList()))
    }

    @Test
    fun noRules_noBase_returnsNull() {
        assertNull(engine.resolve(emptyList(), base = null, ctx(), userId = null, "feature.flag"))
    }

    @Test
    fun platformMatch_appliesRule() {
        val rules = listOf(rule(RuleConditions(platforms = setOf("ios"))))
        assertEquals(base, resolve(rules, ctx(platform = ClientContext.Platform.Android)))
        assertEquals(JsonPrimitive("on"), resolve(rules, ctx(platform = ClientContext.Platform.iOS)))
    }

    @Test
    fun versionCodeRange_isInclusive() {
        val rules = listOf(rule(RuleConditions(minVersionCode = 50, maxVersionCode = 150)))
        assertEquals(JsonPrimitive("on"), resolve(rules, ctx(buildNumber = 50)))
        assertEquals(JsonPrimitive("on"), resolve(rules, ctx(buildNumber = 150)))
        assertEquals(base, resolve(rules, ctx(buildNumber = 49)))
        assertEquals(base, resolve(rules, ctx(buildNumber = 151)))
        // No build number sent → a set bound fails closed.
        assertEquals(base, resolve(rules, ctx(buildNumber = null)))
    }

    @Test
    fun appVersion_greaterThan_isExclusiveAtTheBound() {
        // "app version > 1.0.1"
        val rules = listOf(
            rule(RuleConditions(minAppVersion = "1.0.1", minAppVersionInclusive = false)),
        )
        assertEquals(base, resolve(rules, ctx(appVersion = "1.0.0")))
        assertEquals(base, resolve(rules, ctx(appVersion = "1.0.1")))
        assertEquals(JsonPrimitive("on"), resolve(rules, ctx(appVersion = "1.0.2")))
        assertEquals(JsonPrimitive("on"), resolve(rules, ctx(appVersion = "1.1.0")))
        assertEquals(JsonPrimitive("on"), resolve(rules, ctx(appVersion = "2.0.0")))
        // No app version sent → a set bound fails closed.
        assertEquals(base, resolve(rules, ctx(appVersion = null)))
    }

    @Test
    fun appVersion_range_isInclusiveAtBothEnds() {
        // 1.0.0 ≤ version ≤ 2.0.0
        val rules = listOf(rule(RuleConditions(minAppVersion = "1.0.0", maxAppVersion = "2.0.0")))
        assertEquals(JsonPrimitive("on"), resolve(rules, ctx(appVersion = "1.0.0")))
        assertEquals(JsonPrimitive("on"), resolve(rules, ctx(appVersion = "1.5.3")))
        assertEquals(JsonPrimitive("on"), resolve(rules, ctx(appVersion = "2.0.0")))
        assertEquals(base, resolve(rules, ctx(appVersion = "0.9.9")))
        assertEquals(base, resolve(rules, ctx(appVersion = "2.0.1")))
    }

    @Test
    fun appVersion_preReleaseRanksBelowRelease() {
        assertTrue(SemVer.compare("1.0.0-rc1", "1.0.0") < 0)
        assertTrue(SemVer.compare("1.0.0", "1.0.0") == 0)
        assertTrue(SemVer.compare("1.2.0", "1.10.0") < 0) // numeric, not lexical
        assertTrue(SemVer.compare("1.0.0-rc2", "1.0.0-rc1") > 0)
        // A "> 1.0.0" bound excludes the 1.0.0 pre-releases too.
        val rules = listOf(
            rule(RuleConditions(minAppVersion = "1.0.0", minAppVersionInclusive = false)),
        )
        assertEquals(base, resolve(rules, ctx(appVersion = "1.0.0-rc1")))
        assertEquals(JsonPrimitive("on"), resolve(rules, ctx(appVersion = "1.0.1")))
    }

    @Test
    fun country_and_locale_match() {
        assertEquals(
            JsonPrimitive("on"),
            resolve(listOf(rule(RuleConditions(countries = setOf("US")))), ctx(country = "US")),
        )
        assertEquals(
            base,
            resolve(listOf(rule(RuleConditions(countries = setOf("CA")))), ctx(country = "US")),
        )
        // Locale matches on primary subtag, case/region-insensitive.
        assertEquals(
            JsonPrimitive("on"),
            resolve(listOf(rule(RuleConditions(locales = setOf("en")))), ctx(locales = listOf("en-GB"))),
        )
        assertEquals(
            base,
            resolve(listOf(rule(RuleConditions(locales = setOf("es")))), ctx(locales = listOf("en-US"))),
        )
    }

    @Test
    fun userAllow_requiresKnownUserInSet() {
        val target = UserId(UUID.randomUUID())
        val other = UserId(UUID.randomUUID())
        val rules = listOf(rule(RuleConditions(userAllow = setOf(target.value.toString()))))
        assertEquals(JsonPrimitive("on"), resolve(rules, userId = target))
        assertEquals(base, resolve(rules, userId = other))
        // Anonymous caller can never satisfy an allow-list.
        assertEquals(base, resolve(rules, userId = null))
    }

    @Test
    fun userDeny_vetoesOtherwiseMatchingRule() {
        val denied = UserId(UUID.randomUUID())
        val rules = listOf(
            rule(RuleConditions(platforms = setOf("android"), userDeny = setOf(denied.value.toString()))),
        )
        assertEquals(base, resolve(rules, userId = denied))
        assertEquals(JsonPrimitive("on"), resolve(rules, userId = UserId(UUID.randomUUID())))
    }

    @Test
    fun lowestPriorityRule_winsFirstMatch() {
        val rules = listOf(
            rule(RuleConditions(), value = JsonPrimitive("low"), priority = 10),
            rule(RuleConditions(), value = JsonPrimitive("high"), priority = 1),
        )
        assertEquals(JsonPrimitive("high"), resolve(rules))
    }

    @Test
    fun disabledRule_isSkipped() {
        val rules = listOf(rule(RuleConditions(), value = JsonPrimitive("on"), enabled = false))
        assertEquals(base, resolve(rules))
    }

    @Test
    fun rollout_zeroPercent_neverMatches_hundredPercent_alwaysMatches() {
        assertEquals(base, resolve(listOf(rule(RuleConditions(rolloutPercent = 0)))))
        assertEquals(JsonPrimitive("on"), resolve(listOf(rule(RuleConditions(rolloutPercent = 100)))))
    }

    @Test
    fun rollout_isDeterministic_forSameInstall() {
        val rules = listOf(rule(RuleConditions(rolloutPercent = 50)))
        val first = resolve(rules, ctx(installId = "stable-install"))
        repeat(20) {
            assertEquals(first, resolve(rules, ctx(installId = "stable-install")))
        }
    }

    @Test
    fun rollout_isMonotonic_rampingPercentageOnlyAddsBuckets() {
        // A given install that's "in" at X% must still be "in" at any Y% > X.
        val installs = (0 until 200).map { "install-$it" }
        var previousIn = emptySet<String>()
        for (percent in listOf(10, 25, 50, 75, 100)) {
            val rules = listOf(rule(RuleConditions(rolloutPercent = percent)))
            val nowIn = installs.filter {
                resolve(rules, ctx(installId = it)) == JsonPrimitive("on")
            }.toSet()
            assertTrue(
                previousIn.all { it in nowIn },
                "ramping to $percent% dropped a previously-included install",
            )
            previousIn = nowIn
        }
        // Sanity: ~50% of installs land in a 50% rollout (wide tolerance).
        val rules = listOf(rule(RuleConditions(rolloutPercent = 50)))
        val inAtFifty = installs.count { resolve(rules, ctx(installId = it)) == JsonPrimitive("on") }
        assertTrue(inAtFifty in 70..130, "expected roughly half of 200 installs, got $inAtFifty")
    }
}
