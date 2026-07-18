# In-flight (this cycle)

## refactor(auth): typed AuthOutcome classifier for sign-in routing (AUTH-22)

**Problem:** Onboarding and verify-email each rebuilt new-vs-returning from a raw `profileRepository.resolveIsNewAccount()` boolean (`isBrandNewAccount()` helpers), instead of receiving a typed `SignedUp` / `SignedIn` / `Linked` outcome.

**Approach:** Added `AuthOutcome` + an injectable `AuthOutcomeClassifier` (`DefaultAuthOutcomeClassifier`, backed by the existing `resolveIsNewAccount()` latch). `OnboardingViewModel` and `VerifyEmailViewModel` now branch on the typed outcome; the duplicated `isBrandNewAccount()` helpers are gone and VerifyEmail dropped its direct `ProfileRepository` dependency. **Design call:** the item's acceptance said "`AuthRepository` sign-in/link entry points return a typed `AuthOutcome`," but I put classification in a standalone classifier *above* both repos instead — folding it onto `AuthRepository` creates a DI cycle (`ProfileRepositoryImpl` already depends on `AuthRepository`, and the new-account signal is a one-shot `/v1/me` latch owned by `ProfileRepository` that the Home welcome also observes, so a second consumer risks double-reading it). Rejected the fuller `AccountClaimer` facade (unify every sign-in/link method under one `AuthResult`) as too big for a P2 slice. Full rationale in docs/decisions.md (2026-07-18).

**Reviewer notes:** The static-`Linked` claim paths (`ClaimAccountViewModel`, `finishAppleSignIn`) were left branching on `LinkIdentityOutcome` — they never used the boolean, so rerouting them through the classifier would be pure ceremony. The onboarding sign-in sites can't produce `Linked` (Welcome has no anon guest to link onto), so those `when`s group `SignedIn, Linked -> Home`; noted inline. Behavior is unchanged — the pre-existing onboarding/verify VM suites pass as-is, driven through the classifier via the same `profile.isNewAccount` fake.

**Deferred:** A unified single-entry-point `AccountClaimer` returning one typed `AuthResult` across all sign-in/link methods (the literal "auth entry points return AuthOutcome" end state) — reviewer please triage whether it's worth a backlog item. Nothing filed yet.
