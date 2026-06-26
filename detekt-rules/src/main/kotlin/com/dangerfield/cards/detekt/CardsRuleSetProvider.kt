package com.dangerfield.cards.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * Registers the Cards custom rule set. Discovered by detekt via the
 * `META-INF/services/dev.detekt.api.RuleSetProvider` entry. The rule set id
 * (`cards`) namespaces the rules in `detekt.yml`.
 */
class CardsRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("cards")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ::VerifyStrings,
        ),
    )
}
