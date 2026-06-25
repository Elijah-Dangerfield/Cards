# In-flight log

## chore(onboarding): resource the consent legal-link labels (AUTH-7)

**Problem:** The Welcome consent line's two tappable link labels were inline literals (`link("Terms of Service")` / `link("Privacy Policy")`) while the surrounding sentence was already resourced.
**Approach:** Added `onboarding_welcome_consent_terms_link` / `onboarding_welcome_consent_privacy_link` next to `onboarding_welcome_consent`, and resolve them via `stringResource` before the `buildClickableText` builder (the builder lambda isn't `@Composable`, so the resolves happen outside it in a `run {}`). Labels still match the substrings inside the consent sentence so the spans line up.
**Reviewer notes:** None.
