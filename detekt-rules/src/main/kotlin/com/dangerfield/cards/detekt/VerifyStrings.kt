package com.dangerfield.cards.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Fails on user-facing copy hardcoded as a string literal passed to a DS text
 * composable (`Text` and its wrappers — see [TEXT_CALLEES]), instead of coming
 * from `stringResource(...)` (backed by `:libraries:resources`).
 *
 * Deliberately allows:
 * - `stringResource(...)` / any non-literal expression (a variable, a server-
 *   supplied string) — only a bare literal is a violation;
 * - glyph-only literals (emoji / symbols with no letters), e.g. `Text("📧")`;
 * - literals inside an `@Preview` composable (preview-only sample copy).
 *
 * Scope (v1): pure string literals only. An *interpolated* literal
 * (`Text("Hi $name")`) is not flagged — parameterised copy is a follow-up.
 *
 * Adding a rule is a new [Rule] subclass plus its constructor reference in
 * [CardsRuleSetProvider]; widening coverage to another text sink is a new entry
 * in [TEXT_CALLEES].
 */
class VerifyStrings(config: Config) : Rule(
    config,
    "User-facing copy must come from string resources (stringResource), not inline string literals.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text !in TEXT_CALLEES) return
        if (expression.isInsidePreview()) return
        for (argument in expression.valueArguments) {
            val template = argument.getArgumentExpression() as? KtStringTemplateExpression ?: continue
            if (template.entries.any { it is KtStringTemplateEntryWithExpression }) continue
            val literal = template.entries.joinToString(separator = "") { it.text }
            if (literal.none { it.isLetter() }) continue
            report(
                Finding(
                    Entity.from(template),
                    "Inline user-facing string \"$literal\" passed to ${expression.calleeExpression?.text}() — " +
                        "use stringResource(...) from :libraries:resources instead.",
                ),
            )
        }
    }

    private fun KtCallExpression.isInsidePreview(): Boolean {
        val function = getParentOfType<KtNamedFunction>(strict = true) ?: return false
        return function.annotationEntries.any { it.shortName?.asString() == "Preview" }
    }

    private companion object {
        // The DS text composables that render a user-facing String argument. Their
        // wrappers (e.g. OutlinedText) pass the string on to Text internally, but
        // the literal lives at the wrapper call site, so each is checked directly.
        // AsteriskText is absent on purpose — it takes a composable lambda, whose
        // inner Text is already covered.
        val TEXT_CALLEES = setOf("Text", "OutlinedText", "ClickableText", "BoldPrefixedText")
    }
}
