# In-flight log

## chore(onboarding): resource the consent legal-link labels (AUTH-7)

**Problem:** The Welcome consent line's two tappable link labels were inline literals (`link("Terms of Service")` / `link("Privacy Policy")`) while the surrounding sentence was already resourced.
**Approach:** Added `onboarding_welcome_consent_terms_link` / `onboarding_welcome_consent_privacy_link` next to `onboarding_welcome_consent`, and resolve them via `stringResource` before the `buildClickableText` builder (the builder lambda isn't `@Composable`, so the resolves happen outside it in a `run {}`). Labels still match the substrings inside the consent sentence so the spans line up.
**Reviewer notes:** None.

## Cycle note — PROG-1 left unpicked (deliberate)

PROG-1 (achievement engine → predicates over server `PlayerStats`) was the meaty candidate this cycle but I left it rather than ship a muddying partial. The risk: the unlock write path in `AchievementRepositoryImpl.recordHand` grants real chips (`chipsRepository.addChips(...)`) and XP, and the clean conversion needs a new persisted `claimed_at_value` scheme (a schema addition to the achievement tables) plus a redesign of unlock-once semantics. `PlayerStats` only exposes a subset of what the engine tracks (`handsPlayed` / `handsWon` / `botHandsPlayed` / current+best no-bust streak / `perBotWins`); the many local-only counters (good folds, all-ins, comebacks, pot high-water marks, busts-dealt, level) have no server-authoritative source, so a half-migration would split the source of truth between `PlayerStats` (for the convertible predicates) and the local counter table (for the rest) — arguably worse than today's single local source and likely to read as "PROG-1 done" when it isn't. This belongs in a focused commit that lands the `claimed_at_value` storage + the read/write switch together with the existing unlock tests green, not a nibble. Flagging so the reviewer knows the P1 was assessed, not missed.
