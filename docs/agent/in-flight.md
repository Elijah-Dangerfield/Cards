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

## chore(server): trace ws_send publisher fan-out (slice)

**Problem:** The gameplay path traces `submit_intent` / `start_hand` / `next_hand`, but the publisher → `sendJson` fan-out (the outbound leg) had zero spans.
**Approach (sliced — loud direction call):** Added a `sendTraced` helper wrapping each publisher `sendJson` in a `ws_send` span (room, recipient `user.id`, frame type), applied to both the room-flow and game-state publishers. Deliberately did **not** parent these to `submit_intent` this cycle: there's no central broadcast loop (fan-out is each socket's own collector of the shared `StateFlow`/`SharedFlow`), so linking back would require threading OTel `Context` through `GameSession`'s domain flows — a coupling decision (envelope vs. span-links, plus `StateFlow` conflation making attribution approximate) I didn't want to make unilaterally. Sliced the todo to that remaining gap.
**Reviewer notes:** Span *emission* isn't unit-tested — there's no in-process `SpanExporter` harness, and adding one is a larger lift (deferred). Behaviour (frames still delivered) is covered by the existing `RoomSocketRoutesTest`, which I ran green. Named the span `ws_send` (underscore) to match existing span names (`submit_intent`, `engine.apply_intent`) rather than the todo's `ws-send`. Minor cost: a `withContext(asContextElement())` per outbound frame on the hot path — negligible under the default noop SDK, a span alloc + context switch when an exporter is wired.
**Deferred:** A `SpanExporter`-backed test harness for asserting span shape — reviewer please triage; nothing filed yet.

## feat(server): link ws_send event spans to submit_intent (slice)

**Problem:** Per-recipient `ws_send` fan-out spans were roots — a broadcast wasn't tied to the `submit_intent` that caused it.
**Approach (sliced — loud direction call):** Took the todo's recommended "un-conflated `SharedFlow<GameEvent>` first" path. `GameSession.events` now carries a `TracedGameEvent(event, originSpanContext)` envelope — the span context is captured via `Span.current().spanContext` at emit time (inside the `state_mutate` / `start_hand` span, i.e. under `submit_intent`). The room socket's game publisher threads that context through a private `OutboundGameFrame` carrier and `sendTraced` adds it as an OTel span **link** (`Span.addLink`, guarded on `isValid`) on the `GameEventOccurred` `ws_send` span. Chose span *links* over reparenting because the fan-out is async (each socket's own collector of a shared flow) — a link is the OTel-correct primitive for that, and it keeps the lobby/state legs untouched. Envelope rides on the events flow, not the domain `GameState`, so tracing never leaks into gameplay types. **Deliberately did NOT do the `GameStateSnapshot` (`StateFlow`) leg** — conflation makes per-value attribution approximate; sliced that into the rewritten todo item.
**Reviewer notes:** Correcting the prior `ws_send` block's claim that "there's no in-process `SpanExporter` harness" — there is: `TelemetryTest` installs a global SDK + `InMemorySpanExporter`. Added two tests there asserting emitted events carry the emitting span's context (start_hand leg + state_mutate leg, same trace). The `addLink` call itself isn't asserted end-to-end (that'd need a live `WebSocketServerSession`); it's a guarded one-liner over the now-tested envelope. Link points at the *emitting* span (`state_mutate`/`start_hand`), which sits under `submit_intent` in the tree — if the reviewer wants the link to target the `submit_intent` root directly, that's a one-line change to where the context is captured.
