# In-flight

Per-commit handoff notes for the reviewer. One block per commit this cycle.

## fix(progression): scroll multi-achievement celebration sheet

**Problem:** When several achievements unlock at once, the celebration sheet stacked the cards without scrolling, so they overflowed / clipped past the sheet's max height.
**Approach:** Switched `AchievementCelebrationSheet` from the all-in-one composite-title `BottomSheet` overload to the base overload — title is now `stickyTopContent`, the cards live in a `verticalScroll` `content`, and the continue button is pinned as `stickyBottomContent`. This leans on `ModalContent`'s existing weighted-middle layout (the same scroll pattern `PreviewModalContentLong` demonstrates) instead of adding a height param to the DS.
**Reviewer notes:** Pure layout change, no unit test (covered by the existing stacked-multiple preview). Note the todo hint said the sheet lives in `:features:progression:impl`; it's actually in `:features:room:impl` — fixed the work, the hint was just wrong.

## feat(progression): loss-disclosure on Stats page for anonymous users

**Problem:** Anonymous users past level 1 had no nudge on the Stats page to claim their account, so they could lose progress with no warning.
**Approach:** `StatsViewModel` now observes `AuthRepository.observe()` on its own collector (kept separate from the 3-flow data `combine` so auth resolution can't stall the stats render) and exposes `isAnonymous`. `StatsScreen` renders a compact `ClaimDisclosureCard` (DS `Surface`, surfacePrimary, accentPrimary CTA) under the XP hero only when `isAnonymous && level > 1`; the entry point routes its tap to `ClaimAccountRoute`. Added `features:profile` as a dep on `:features:progression:impl` for the route.
**Reviewer notes:** Direction call — I placed the disclosure right under the XP hero (where the user just saw the level/XP it protects) rather than at the bottom; easy to move. Copy lives in new `stats_claim_disclosure_*` strings. VM tests cover anonymous→true / claimed→false; the card itself has a preview.

## feat(shop): gate real-money IAP behind account claim

**Problem:** An anonymous user could complete a real-money chip-pack purchase, so a lost/un-claimed account meant paid chips vanished. `launchIapPurchase` only blocked the no-session case (`userId == null`).
**Approach:** `launchIapPurchase` now reads the full `AuthState.Authenticated`; if `isAnonymous`, it emits a new `ShopEvent.ClaimAccountRequired` (distinct from `NotSignedIn`) before touching `BillingClient`, and the entry point routes that to `ClaimAccountRoute`. A claimed user purchases unchanged. Chose a dedicated event over reusing `NotSignedIn` because the intents differ (route-to-claim vs. show store-unavailable toast); the sealed `when` forced the entry point to handle it.
**Reviewer notes:** Renamed the old `confirmIapPack_anonymousUser_…` test to `confirmIapPack_noSession_…` (it actually exercised the `Unauthenticated` path and its comment now contradicted the gate) and added a real anonymous-authenticated test asserting no billing call + `ClaimAccountRequired`. Decision is already logged in `docs/decisions.md` per the todo.
