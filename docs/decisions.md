# Decision Log

Decisions made about Cards' product direction and architecture. Append new decisions; do not rewrite history.

The canonical V1 plan lives at `~/.claude/plans/this-is-going-to-vast-kahn.md` outside the repo; this log is for in-repo continuity and future sessions.

## What goes here

**Add an entry when** — you've made a non-trivial call that future-you (or a future agent) would otherwise *re-derive*: a new module boundary, choice of library, a scope cut, a schema shape, an explicit rejection of an obvious-looking alternative, anything where the reason matters more than the change.

**Don't add an entry when** — the work speaks for itself (a refactor, a bug fix, a dependency bump, a typo). Code + commit message + PR title is enough. Most commits don't deserve a decision entry.

When a decision becomes settled enough that it reads as "just how the code works," graduate its explainer to a [`wiki/`](./wiki/) file and remove the dated entry here. The log is for live rationale and rejected alternatives, not settled architecture.

## Format

```
## YYYY-MM-DD — <one-line decision>

**Decision:** <what we're doing>
**Why:** <load-bearing reason — what changes if this reason goes away>
**Alternatives considered:** <briefly, with why each was rejected>
**Status:** Locked / Tentative / Superseded by <date>
```

If a later decision supersedes an older one, mark the old one `Superseded by YYYY-MM-DD` in place — don't delete it. Knowing why we used to think X is often the reason future-you doesn't fall back to X.

---

## 2026-06-26 — Room invites share a deep link via a platform `ShareLauncher`, code as a path segment (ROOM-7)

**Decision:** Sharing a room invite goes through a new `ShareLauncher` capability in `:libraries:navigation` (sibling to `WebLinkLauncher`), surfaced as `Router.shareText(text)` and backed by per-platform impls (Android `ACTION_SEND` chooser, iOS `UIActivityViewController`, JVM unsupported). The invite link is `cards://join/{prefilledCode}` — the code is a **path segment**, not a query param — built from one source of truth, `RoomInvite.linkForCode`, which the lobby deep-link registration also references so the share URL and the registered deep link can't drift. Every room already carries a shareable code regardless of visibility, so the affordance is identical for private/open/public rooms; "public" only adds Find-a-Table discovery on top.

**Why:** A share sheet is a fire-and-forget platform side effect with the exact shape of `openWebLink`, so it belongs on `Router` next to it rather than as a one-off in the lobby screen — any future invite surface (friends, achievements) reuses it. A path-segment code keeps the shared URL human-readable (`cards://join/ABC123`) versus a query string, and centralising the link string means the deep-link basePath and the share builder are provably the same.

**Alternatives considered:**
- **Build the share string + call platform APIs inline in `LobbyScreen`.** Rejected: composables don't own platform side effects here (clipboard is the lone exception, and even that is borderline); a share sheet needs the root view controller on iOS, which only the DI-wired impl can reach.
- **Query-param code (`cards://join?prefilledCode=ABC123`).** Works (Navigation matches it), but the shared link reads as machine output. Path segment is friendlier and still resolves through `routeDeepLink<LobbyRoute>`.
- **Server-issued short links / Universal Links (https://).** Deferred — needs an assetlinks.json / apple-app-site-association host and a link-shortening service. The custom `cards://` scheme is already wired on both platforms and ships today.

**Status:** Locked.

---

## 2026-06-25 — Placeholder ($0) room snapshots are dropped in the data layer, not the UI (MP-16)

**Decision:** The "don't show a $0 buy-in" rule lives as one domain invariant, `Room.preferRealOver(previous)` (backed by `Room.isPlaceholder`, i.e. `buyIn <= 0`), applied at every repo staging boundary: `RoomRepositoryImpl.upsertActiveRoom` (HTTP create/join/addBot) and `ReconnectingRoomSocket`'s `Snapshot` emission (the live lobby path). A placeholder snapshot never regresses a known-good room — the repo retains the last real one. The `LobbyScreen` `if (room.buyIn > 0)` band-aid was removed; the UI no longer defends against an impossible state.

**Why:** A $0 room is structurally impossible (create form seeds a default, server rejects out-of-range buy-ins), so `buyIn == 0` provably means "not a real snapshot" — the stale rebound that arrives after the sole other human leaves. The invariant belongs where snapshots are staged, not at each render site: the lobby `room` actually flows through the socket `Snapshot` path, so a repo-only guard would have left the band-aid load-bearing. Putting the rule once in the data layer means the band-aid (and any future render site) needs no `buyIn > 0` defense.

**Altitude:** Chose "don't regress a real room to a placeholder" over "drop $0 snapshots at one boundary." The narrower framing (guard only `upsertActiveRoom`) misses the real rebound path (the socket), and a guard scattered per-callsite is the band-aid we're removing. The general rule, expressed as a pure `Room` function and applied at both staging points, keeps the invariant in one greppable place.

**Alternatives considered:**
- **Guard only `upsertActiveRoom`.** What the todo literally pointed at. Rejected: the lobby's `room` is fed by the socket `Snapshot` emission, not `upsertActiveRoom`, so this alone wouldn't satisfy the acceptance and the band-aid would have to stay.
- **Keep the `LobbyScreen` guard.** Rejected: pushes an impossible-state defense into the UI; every future room-rendering surface would need to repeat it.
- **`distinctUntilChanged` on the snapshot flow.** Rejected: a placeholder isn't a duplicate, it's a regression; dedup wouldn't catch the real to $0 transition.

**Status:** Locked.

## 2026-06-25 — Match-over result is a play-screen dialog, not a new nav screen (MP-14)

**Decision:** The heads-up match-over "result screen" (MP-14) is a `MatchOverResultDialog` overlay rendered on the existing play screen, sequenced like the bust/showdown dialogs, not a new navigation route. The terminal `match_over_resolved` wire frame closes the socket as a new `ClosedReason.MatchOver(winnerUserId)` (the enum was promoted to a sealed interface to carry the winner id); the VM resolves the local win/loss role and surfaces `PlayPokerState.matchOverResult`, and the dialog's Done CTA fires the same `LeaveGameFromBust` teardown + route-off the bust dialog uses. The live countdown is likewise an on-table banner, not a screen.

**Why:** Every other hand-end result in this feature (showdown, solo bust, MP bust) is a dialog overlay on the play screen with the table visible underneath, and they share the leave/reconcile teardown. A standalone match-over route would fork that established pattern, need its own nav wiring + back-stack reasoning, and lose the "table still behind the scrim" continuity for no user benefit — the match-over is just one more terminal hand-end shape. Keeping it a dialog reuses the rebuy action, the leave-and-reconcile path, and the dialog DS primitives.

**Alternatives considered:**
- **Dedicated `MatchOverRoute` screen.** Real nav target, own VM. Rejected: heavier than the moment warrants, forks the hand-end-result pattern, and the AGENTS bottom-sheet/dialog guidance points the other way for transient terminal overlays.
- **Keep `ClosedReason` an enum, pass the winner id out-of-band.** Would need a parallel channel for the winner id alongside the close reason. Rejected: the reason is exactly where "who won" belongs; a sealed interface carries it cleanly and the other reasons stay data objects.

**Status:** Locked.

## 2026-06-24 — `DELETE /v1/rooms/{code}/me` (leave) is idempotent: 204 across the board

**Decision:** Leaving a room returns 204 whether the caller is a current member, was never a member, or the room is already gone. The route no longer maps `LeaveResult.RoomNotFound`→404 or `LeaveResult.NotInRoom`→409; the service still returns those distinct results, only the HTTP projection collapses them.

**Why:** The caller's goal is "I am not in this room." Once that's true, a non-2xx only reads as a dead leave button. CARDS-2R saw `DELETE /me` 409-loop for ~90s while a room settled after an opponent crashed (the surviving human's membership transiently read as gone), and CARDS-34 saw a re-issued leave 404 after the membership was already cleared — both surfaced as "leave didn't work." Idempotency makes a re-tap, a post-settlement leave, and a double-fire all succeed.

**Alternatives considered:**
- **Queue the leave server-side during settlement, keep 409 otherwise.** More machinery (a pending-leave set + drain) to preserve a distinction no caller acts on — the client already maps 404/409 to a success-equivalent and the lobby treats them as `resetToIdle(error = null)`. Rejected as over-built for the same end-state.
- **Fix only the client (treat 404/409 as success everywhere).** Already largely true, but leaves the server emitting misleading error envelopes other clients/tools would have to special-case. The honest fix is at the contract.

**Status:** Locked.

## 2026-06-24 — Branching: trunk-based, no release branches

**Decision:** Stay on trunk-based development. `main` is always shippable, short-lived branches merge into `main`, releases are tags on `main` cut by release-please. No release branches, no GitFlow, no long-lived `develop` line (the `develop` branch we use is a worker-staging area for the nightly bot, not a release-stabilization branch).

**Why:** release-please is aligned with TBD. Solo dev + store-submitted app + occasional store rejection means the retag cost is real but manageable (~1-2 retags per version at worst). Release branches would add real machinery — dual release-please configs, a merge-back ritual — to solve a problem we hit maybe once or twice per version. The smoother-ritual options (one-shot retag action, skip-play signal on tag push) are ~1 hour of work and cover 80% of the pain without changing the model.

**Alternatives considered:**
- **GitFlow (classic).** Long-lived `develop` + `release/*` branches + `master` for production. Designed for periodic boxed releases on a schedule; the original author has since added a disclaimer it's outdated for most teams. Rejected: high coordination overhead for a solo dev shipping continuously to two app stores.
- **Release branches on top of trunk-based (the practical middle ground).** Cut a `release/vX` branch at version freeze; only stabilization commits go there; merge back to main. Earns its keep with multiple in-flight versions or LTS support. Rejected: we don't have multiple in-flight versions yet, and the dual release-please config would be a tax on every release.
- **GitHub Flow (TBD's simpler cousin).** Effectively what we do. The distinction-without-difference vs. TBD is rhetorical.

**Revisit when:** we hire a second developer, ship multiple major versions needing long-term support, or move to a cadence where v-next is actively underway while v-current is in Apple review. The full essay-form rationale (the four-model walkthrough, what companies actually do, what solo devs actually do, the case for/against release branches in this repo specifically) was preserved in git at `docs/branching-and-release-strategy.md` before its deletion in this commit.

**Status:** Locked.

---

## 2026-06-24 — Banned-account gate lives in the JWT validate→challenge flow, not a post-auth plugin

**Decision:** The server blocks a banned caller (native `auth.users.banned_until`) inside the existing JWT provider's `validate`/`challenge` path: `validate` resolves the user id, calls `ModerationRepository.banStatusFor`, and on a live ban stashes a locked `AccessDeniedResponse` on the call + returns no principal; `challenge` renders that stash as `403 {reason, until, appealUrl}` (else the usual `401`). A `BanGate(moderation, appealUrl)` is threaded into `installAuthentication`. Reasons are `banned` only today (the native flag carries no reason); the lookup fails **open** on a DB hiccup. Wire fields are camelCase to match the rest of the server JSON contract (`MeResponse.isAnonymous`), not the todo's illustrative `appeal_url`.
**Why:** Responding from an `on(AuthenticationChecked)` application-plugin hook does **not** halt the routing pipeline — the route handler still runs and overwrites the response (observed: banned calls returned `200`). The auth provider's validate→challenge is the one place Ktor reliably short-circuits routing (it's how the `401` already works), so a banned caller provably never reaches a handler.
**Alternatives considered:** *A standalone `createApplicationPlugin` on `AuthenticationChecked` that responds 403* — rejected: doesn't short-circuit (proven by a red test). *Throwing a typed exception caught by `StatusPages`* — rejected: exceptions thrown from the auth hook didn't propagate to StatusPages (still `200`). *A new moderation table for suspended-vs-banned + appeal URL* — deferred: the native flag only carries banned-until, and a richer split isn't needed for the minimum "don't let banned users keep playing" slice.
**Status:** Locked (server half). Client half — parse the `403` and route to `BlockingErrorScreen` instead of the generic session-expiry screen — is the remaining slice in `docs/todo.md`.

---

## 2026-06-20 — Friend graph: one canonical-pair row + an `acted_by` direction marker

**Decision:** Model `friend_relations` as one row per *unordered* user pair — `user_a` is always the lexicographically smaller UUID (a DB `CHECK (user_a < user_b)` enforces it) — with `state ∈ {requested, accepted, blocked}` and an extra `acted_by` column recording the user who set the current state (the request sender, the blocker). The repository canonicalises every pair before reading/writing by comparing the lowercase-hex UUID string, which orders identically to Postgres's `uuid <` operator.
**Why:** The spec required the row to be "unique regardless of direction" (no both-(x,y)-and-(y,x)). A single canonical row satisfies that, but then nothing records who requested whom or who blocked whom — which the inbox ("requests *to* me") and block semantics both need. `acted_by` is the minimal addition that restores direction without a second row. Comparing UUID *strings* (not `java.util.UUID.compareTo`, which is signed-bits) is what keeps the Kotlin canonicalisation in lockstep with the SQL `CHECK` — get this wrong and you get either duplicate-direction rows or constraint violations.
**Alternatives considered:** *Two directed rows per pair* — rejected: breaks the uniqueness requirement and doubles every read. *No direction column, infer from a separate requests table* — rejected: a second table for what one column carries. *`UUID.compareTo` for canonical order* — rejected: its signed-long comparison disagrees with Postgres `uuid <` for high-bit ids, silently desyncing app and DB.
**Status:** Locked. (Schema + endpoints shipped; the recently-played-with send gate is the one remaining slice, blocked on the recently-played-with record.)

---

## 2026-06-19 — Opponent level over the wire freezes per session (mirrors badges/avatar)

**Decision:** MP opponents' levels are rendered from a new `Seat.xp` field the server snapshots once at hand-start (`RoomSocketRoutes.handleStartHand` resolves each member's `ProgressionRepository.find(userId)?.totalXp`), copied onto the engine `Seat` and preserved across hands in `GameSession.requestNextHand` — exactly the path `badgeProductIds` and `avatarEmoji` already take. The client derives the level locally via `levelProgressFor(seat.xp).level` in `TableUiState.badgeFor` and `occupantsFor`. Level is therefore **frozen for the lifetime of the session**: a player who levels up mid-session keeps their start-of-session level on opponents' screens until a fresh session.

**Why:** The todo flagged a real choice — re-resolve XP on every `RequestNextHand` so levels tick up mid-session, or freeze per session like badges. Freezing is the consistent, lower-risk call: it reuses the existing avatar/badge resolution seam verbatim (one resolve site, no new repo plumbing into the `GameSession`/registry `requestNextHand` path, which has no repository access today), and a stale-by-one-session opponent level is cosmetic and self-corrects next session. Re-resolving fresh would mean threading `ProgressionRepository` down into the registry's next-hand path purely to make a cosmetic pill tick — not worth the coupling for V1.

**Alternatives considered:** (1) **Re-resolve on `RequestNextHand`** — rejected for V1: cosmetic benefit, real coupling cost (repo into the registry/session next-hand path). Revisit if a "leveled up at the table" celebration ever wants live opponent levels. (2) **Send the derived level (Int) over the wire instead of raw XP** — rejected: XP is the canonical value and the client already owns the curve (`levelProgressFor`); sending XP keeps one source of truth and lets the curve change client-side without a server change. The richer tapped-opponent Player Card (badges + title + level) in `backlog.md` reuses this same `Seat.xp`.

**Status:** Shipped — `Seat.xp` plumbed end-to-end; level renders on opponent seats. Needs a server deploy to populate XP (pre-deploy, `find` returns the row or null and the pill omits gracefully).

---

## 2026-06-15 — Launch shape: monetized + full public (V1)

**Decision:** V1 ships as a **full public** launch (not a closed beta) **selling chip packs (real-money IAP) from day one.**
**Why:** This is the chosen rollout for V1; it's logged because it's the gating decision that puts several items on the **hard critical path** a free-or-beta launch could have deferred. If we ever switch to free-at-launch (billing flagged off) or a beta-first track, most of the consequences below relax.
**Consequences (now critical-path, not deferrable):**
- **Server-side IAP receipt validation + server-authoritative purchase ledger** before any sale — the client currently trusts the receipt and credits chips locally. ([todo.md §C Billing integrity](./todo.md).)
- **Store IAP products + pricing + store API credentials** (developer-todo) — these gate the receipt-validation work, and have lead time, so start them first.
- **Full legal/compliance up front:** ToS/Privacy, store data-safety disclosures, age/content ratings, support + web-deletion URLs, LLC/insurance (developer-todo legal).
- **Prod DB backups / PITR** before real balances exist (developer-todo dashboard).
- **Public-MP quality gates** (per-turn timer, orphaned-room forfeit — todo.md B3) — no beta to shake them out.
**Recommended first code item:** DB-backed config Phase 1 ([todo.md §C](./todo.md)) — unblocked now, gives an IAP kill switch + live `minSupportedVersionCode`, and is a launch-day safety net. Receipt validation jumps to the top once store IAP products + credentials exist.
**Status:** Locked (revisit only if Elijah switches to free-at-launch or beta-first).

---

## 2026-06-09 — Central, declarative auth-gate on navigation

**Decision:** A route declares what identity it needs via `Route.authRequirement` (`None` / `Account` / `ClaimedAccount`). `DelegatingRouter.navigate()` consults an injected `AuthGateChecker` and, when the requirement isn't met, transparently substitutes a shared `AuthGateRoute` (a bottom sheet) for the requested route — copy/CTA chosen from a `GateReason` (finishing-setup / need-account / need-claimed). First applied to `LobbyRoute` + `PlayMultiplayerRoute` (`Account`).

**Why:** Gating is a cross-cutting concern that should be declared once per feature and enforced in one place. `navigate()` is the single choke point for all navigation, so enforcing there is proactive (blocks before the screen renders *and* before any authed call fires) and uniform. Adding a gate to a new feature is one constructor arg on its route — no per-screen guard code.

**Decoupling:** `AuthRequirement` / `AuthGateChecker` / `AuthGateRoute` live in `:libraries:navigation` (just markers + an interface). `RealAuthGateChecker` (in `:navigation:impl`, which gained an `:libraries:identity` dep) caches `AuthState` + `GuestAccountCreator.state` from their flows so the check is a synchronous peek (navigate isn't suspend) and fails *closed* before auth resolves. It's an `AutoInit`, not an `AppEventListener`, to avoid the `AppEventBus` DI cycle. The gate sheet lives in `:apps:compose` because its CTAs span onboarding + claim.

**Alternatives considered:**
- *Throw `AuthError`, catch → error page* — rejected: reactive (you've entered the feature / fired the 401 before bouncing), scattered across call sites, control-flow-by-exception.
- *A `RequireAccount { … }` composable wrapper per screen* — rejected (and explicitly disliked as a web-ish pattern): per-feature, and still reactive (navigate-then-bounce flicker) rather than proactive.

**Status:** Locked. Note: route-gating covers *navigations*, not in-screen *actions* — real-money purchase buttons (an in-screen action) still need a VM-level `isAnonymous` check; `ClaimedAccount` is ready for any checkout *route*.

---

## 2026-05-30 — Multiplayer host = first connected member (implicit auto-promotion)

**Decision:** The lobby's "effective host" is computed client-side as `room.members.firstOrNull { it.isConnected }?.userId`, not the server-tagged `room.hostUserId`. The host badge, the "Start hand" CTA, and the snackbar promotion notification all key off this computed value. When the original host disconnects (`isConnected = false`), the next-listed connected member becomes effective host automatically with no server change.

**Why:** The acceptance criterion for V1 multiplayer ("two humans play a full hand") implies one player can start the hand, and that role must survive a disconnect mid-session. The two viable shapes for host-departure were (a) auto-promote silently, (b) kill the room. Killing every room when the host steps away is hostile UX (everyone in the middle of a hand gets bumped). Auto-promoting is the obviously-warmer option — the question was whether to add server state for it or derive it client-side. Deriving from `members.firstOrNull { isConnected }` is a one-liner, requires zero server changes, and preserves in-progress hands (the engine is server-authoritative; promotion only matters for the *next* `StartHand` frame).

**Alternatives considered:**
 - **(a) Server-side promotion** — add `currentHostUserId` to the room state, server reassigns on disconnect, broadcasts updated snapshot. Cleaner conceptually but couples a UX call to a wire-format change for no client-visible delta over the derived approach.
 - **(b) Kill the room on host leave** — closes every in-flight hand whenever someone hosts then drops their connection. Punishes the other players for the host's network blip.
 - **(c) Promote with a grace period** — let the original host reconnect within N seconds before promoting. Needs a server timer + state; not warranted before we see this happen in real playtests.
 - **(d) No promotion (any seated player can start)** — collapses the "who starts" UX to a free-for-all. Cluttered ("two players both see the Start button"). Rejected for V1 — the host concept is the simplest mental model.

**Status:** Locked for V1. Migrate to server-driven host if we ever need it to be observable from non-clients (analytics, server-side moderation, tournaments) or if the silent auto-promotion turns out to confuse remaining players in real playtests.

---

## 2026-05-30 — Trace MP broadcasts via span links on a `TracedGameEvent` envelope

**Decision:** The per-recipient `ws_send` fan-out spans link back to the intent that caused them using OpenTelemetry span *links*, not parent/child reparenting. The originating span context rides on a `TracedGameEvent(event, originSpanContext)` envelope wrapping `GameSession.events` (`SharedFlow`); the socket publisher reads it off the envelope and `addLink`s it onto the `GameEventOccurred` `ws_send` span. The conflated game-state `StateFlow` leg is left unlinked for now.
**Why:** The fan-out is genuinely asynchronous — there's no central broadcast loop; each socket independently collects a shared flow, so a single state mutation produces N sends across N coroutines. A span *link* is the OTel-correct primitive for "this work was caused by, but is not a synchronous child of, that span." Reparenting would force a fake single-parent tree onto a one-to-many async relationship and require threading a live `Context` through the collectors. Putting the context on an envelope (vs. on the domain `GameState`/`GameEvent`) keeps tracing out of the gameplay types.
**Alternatives considered:** (a) Reparent sends under `submit_intent` — wrong shape for async fan-out, and conflation means a `StateFlow`-driven send can't be attributed to one exact intent. (b) Put the context on `GameState` itself — pollutes the domain type that's also persisted to `room_sessions.state_jsonb`. (c) Do the `StateFlow` leg too — deferred because conflation collapses rapid updates, making per-value attribution approximate; sliced into `docs/todo.md`.
**State-snapshot leg (landed 2026-05-30):** Applied the same envelope pattern to the `GameStateSnapshot` leg via a sibling `TracedState(state, originSpanContext)` `StateFlow` — chosen over converting `GameSession.state`'s type (which would ripple through ~30 readers and the persistence path) so every existing `state` reader stays untouched and tracing stays off the domain types. Accepted the approximate-attribution caveat alternative (c) named: `StateFlow` conflation may collapse rapid mutations, so a recipient attaching mid-burst links only to the latest state's origin. The precise per-intent chain is still captured on the un-conflated events leg.
**Status:** Locked for both gameplay legs (events + state snapshot). Lobby snapshots stay unlinked by design.

---

## 2026-05-29 — Multiplayer: snapshot-only state, OTel for debugging

**Decision:** Server-authoritative MP state lives in a single `room_sessions(session_id UUID PRIMARY KEY, state_jsonb JSONB, updated_at TIMESTAMPTZ)` row, overwritten inside the per-session mutex on every mutation. Drop the event-sourced `game_events` write path (it shipped 2026-05-28 against the prior direction and never had a reader). Debugging visibility ("every move on every hand") is provided by OpenTelemetry traces on the server — one trace per `SubmitIntent`, spans for the engine pipeline, attributes for `session_id` / `user_id` / `hand_id`. Sentry covers crash + error capture (single project, platform-tagged).

**Supersedes the 2026-05-27 "Multiplayer: event-sourced game state + persisted room membership" entry.**

**Why this over the prior path:**
- The event log's *only* V1 consumer was crash recovery. A single-row snapshot table accomplishes that in ~20 lines.
- Hand history / spectator hydrate / replay-as-a-feature aren't V1 scope. Designing for them now pays the complexity tax for a future we may not build.
- Per-hand event volume (~15–30 rows) scaled to a real MP userbase = millions of rows per day without a pruning policy. We don't have a pruning policy.
- OTel traces give us *better* "every move debug" visibility than `game_events` would have. Spans carry timing + attributes + cross-service correlation; `game_events` rows are just append-only Postgres tuples.
- The animation-pop on reconnect (without the rolling tail) is a one-frame visual jank, not a correctness issue. The 5-minute disconnect grace means the snapshot a reconnecting client reads is always fresh.

**Alternatives considered:**
- **Keep event-sourced.** Rejected: pays for features V1 doesn't ship; doesn't actually give us "every move debug" (OTel does that better).
- **Hybrid (snapshot-only + rolling tail of last ~50 events for reconnect animation replay).** Parked. Add only if reconnect smoothness becomes a real user complaint — the write path is already mostly written.

**Telemetry:**
- **Sentry — single project, platform-tagged.** Tag every event with `platform=ios|android|server` + `release=<version>`. Multi-project = fragmented alerts + harder cross-platform regression triage.
- **OpenTelemetry — server only for V1, traces *and* logs.** Ktor instrumentation + OTLP exporter handles both signal types. One trace per `SubmitIntent`, spans for validate → engine-resolve → state-mutate → broadcast → per-recipient WS-send. Server logs also flow through the OTel logs SDK so they're auto-tagged with the current `trace_id`, enabling trace ↔ log correlation in Grafana. Client-side OTel deferred; client errors flow through Sentry.
- **Where signals land.** Confirmed via Fly's community thread on Grafana data sources: Fly's bundled `fly-metrics.net` is multi-tenant and locked down to its built-in data sources — users get Editor-only access, can't add Tempo or external endpoints. Their managed Quickwit deployment is also wired for logs only (no Traces tab in their Grafana). So Fly's bundle gives us logs (Quickwit / VictoriaLogs) + metrics (Prometheus); traces have no home there. **Decision: Grafana Cloud is our daily Grafana.** Logs (Loki) + traces (Tempo) ship there via OTel; Fly's Prometheus added as a remote datasource using `flyctl auth token` so infra metrics stay queryable from the same UI. Fly's `fly-metrics.net` stays available for the canned infra dashboards but isn't where we live day-to-day.
- **Collector preference order** if Grafana Cloud is ever outgrown: self-hosted Grafana + Tempo + Loki as a Fly app (Fly's own suggested workaround for the locked bundle), then Honeycomb (paid, best trace-query UX). Captured in [`developer-todo.md`](./developer-todo.md).

**What changes in the spec / code:**
- [`todo.md §B`](./todo.md) rewritten around the snapshot-only direction; `B0` collapsed to one snapshot bullet, `B1` reduced to "snapshot-on-reconnect," `B5` parks the rolling-tail option.
- The shipped `game_events` write path retires in a follow-up commit (P2 in `§B0`). The table either drops or is kept bookmarked for a future hand-history feature.
- New `§C Observability` section in `todo.md` tracks the Sentry + OTel wiring; new dashboard items in `developer-todo.md` track the project / endpoint provisioning.

**Status:** Locked.

---

## 2026-05-29 — RLS enabled (deny-all) on per-user tables

**Decision (landed):** Flipped RLS on for every per-user table — `profiles`, `wallets`, `wallet_events`, `inventory`, `equipment`, `user_messages`, `products`, `room_sessions`, `game_events` — **with no policies**. This is "default-deny against the PostgREST `anon` and `authenticated` roles" — exactly what we want because the client never hits PostgREST directly (all data flows through the Ktor server's service-role JDBC connection, which bypasses RLS).

**Why this isn't the "inert policies false sense of security" trap flagged in the 2026-05-23 entry below:** there are no policies. The wall is hard. Anon clients with the public key can no longer pull rows from `https://yuqrfhdoejonclgbixlw.supabase.co/rest/v1/...`. Authenticated users with a valid JWT also can't (they have no business hitting PostgREST in our architecture). Only service-role connections get through, which is Ktor.

**Triggered by:** Supabase dashboard warning *"This table can be accessed by anyone via the Data API as RLS is disabled."* The warning is correct; the original deferral conflated two different RLS problems:
- **(A) PostgREST anon/authenticated data-API hole** — closed by this entry via deny-all.
- **(B) Per-user policy enforcement** (`USING (auth.uid() = user_id)`) — still deferred per the entry below. Requires the per-request DB role architecture; not worth it for V1, and would still be inert under the current service-role connection.

**Verification:**
```bash
curl "https://yuqrfhdoejonclgbixlw.supabase.co/rest/v1/wallets" \
  -H "apikey: <anon_key>" -H "Authorization: Bearer <anon_key>"
```
Before: returns rows. After: `[]` or permission error. Ktor routes continue working unchanged.

**Status:** Locked.

---

## 2026-05-23 — Split `IdentityRepository` into `AuthRepository` + `ProfileRepository`

**Decision:** The single `IdentityRepository` is split into two narrower repositories with a one-way dependency:
- **`AuthRepository`** owns the Supabase user lifecycle + access token end-to-end. Operations: `current()` / `observe()` (resolved-only — no in-flight sentinel), `accessToken()`, `refreshAccessToken()`, `retry()`, plus sign-in/up/OAuth/link/delete/sign-out flows. There is no separate `AuthTokenProvider` — auth is the producer.
- **`ProfileRepository`** owns `/v1/me` + the local profile cache. Collects `authRepository.observe()`; on every emission, resolves to `Profile.Authenticated` (server) or `Profile.Fallback` (cache → localId UUID) via `Catching { server }.fold(success → write cache, failure → read cache)`. Cache is fallback, not first-frame.
- **`Profile`** is now sealed: `Authenticated` vs `Fallback`. Compiler-enforced gating at call sites — shop hard-gates on Authenticated; offline-browsable surfaces accept either.

`IdentityRepository`, `Identity`, `IdentityState`, `IdentityCache`, `SupabaseIdentityRepository`, `AuthTokenProvider`, `NoOpAuthTokenProvider`, `SupabaseAuthTokenProvider` deleted entirely.

**Why:** Three things conflated under one repo:
1. Auth state changes rarely (sign-in, sign-out, refresh). Profile state changes on every edit and on every server resolve. Different lifecycles, different consumers, different failure modes.
2. The `IdentityState.Unknown` sentinel forced every caller to handle a "we don't know yet" branch. The new design pushes that to the call boundary (`suspend current()` or `.first()` on `observe()`), which is the right place for it.
3. The optimistic cache emit at construction made every consumer race the server resolve — `SignedIn(cached)` would fire before `/v1/me` landed, producing stale-state UI flashes. Cache-as-fallback (only on `onFailure`) removes the race by design.

The trigger was a `401 Unauthorized` on every cold-boot `InventorySync.sync()` call. Investigation revealed the underlying type was over-broad; the fix is the architecture, not just the bug.

**Alternatives considered:**
- **In-place rework of `IdentityRepository`.** The original 2026-05-21 boot-gate decision was a partial fix in this direction (idempotency tightening + `loadTokens` timeout). It worked, but the underlying API kept conflating auth and profile concerns and forced every consumer to learn both. The split is the right shape; the boot-gate fix is superseded.
- **Single repo with two interfaces (`AuthRepository`/`ProfileRepository`) implemented by one class.** Considered to keep the wiring simpler. Rejected: the lifecycles really are different, and having one class implement both means every test fake has to stub both surfaces even for tests that only touch one.
- **Keep `IdentityState.Unknown` and only split the operations.** Rejected: the sentinel was the source of the UI flash bugs. Removing it forces consumers to declare the wait — which is good — and the suspend / replay-1 pattern is mature enough that the ergonomic cost is negligible.

**Status:** Locked. Supersedes the 2026-05-21 "Identity boot gate + network-client token wait" entry below — the `AuthTokenProvider` it added is gone (replaced by `AuthRepository.accessToken()`), the `IdentityCache` it leaned on is gone (replaced by `ProfileCache`), and the optimistic-cache-emit-with-idempotency-recheck contract it tightened is replaced by the simpler suspending `current()` contract.

---

---

## 2026-05-20 — Drop proactive smart-claim prompts; add app-store review prompts in their place

**Decision:** Stop pushing anonymous users to claim. Remove the five-trigger smart-claim-prompts table (first MP win, first Epic+ achievement, 5K balance, first shop visit, Level 10). Claim remains available passively (static Profile card; inline-only at the moments where claim is actually required — host a public room, add a friend). In parallel, *add* app-store review prompts that fire at the positive-moment triggers we just freed up (Epic+ achievement unlock, Level 10, session-end-net-positive), gated by install-age + session-count + 90-day-no-prompt + last-hand-not-a-bust. Use native APIs (SKStoreReviewController / Play In-App Review) only — no self-built rating dialog.

**Why drop claim prompts:** The original case for proactive claim prompts was anti-farming on the starter grant. That exploit is now closed by device-fingerprint deduplication ([§6.1](./product/product-spec.md#anti-farming-on-the-starter-grant)) — claim adds nothing to it. The remaining benefits of claim (durability, friends, leaderboards, public-room hosting) are *for the user*, not for us, and best-effort recovery via fingerprint + iCloud Keychain / Block Store already covers the common case. Pushing users to claim was begging for a conversion metric that wasn't load-bearing — a §10 brand-check violation.

**Why add review prompts:** Those same positive moments (Epic+ achievement unlock, Level 10, net-positive session end) are *legitimately* good moments to ask the user for a kind word — they're feeling good, they've invested, they're not interrupting anything. The native review APIs handle their own throttling (iOS 3/year, Android similar), so calling at the trigger moment doesn't mean prompting at the trigger moment — the OS decides. We add a 7-day install-age gate and a 90-day no-prompt gate as belt-and-suspenders, plus a "last-hand-not-a-bust" check so we never ask after a frustrating moment. App-store rating is load-bearing for ASO (v1-mvp.md §1 target: ≥ 4.3 — doc has since been deleted) in a way claim conversion never was.

**Alternatives considered:**
- **Keep some claim prompts, drop others** (e.g., keep only "first shop purchase" since cosmetic durability is the most concrete pitch). Rejected: any proactive prompt is begging when the underlying need is already met by fingerprinting. Cleaner to drop the surface entirely and let inline-when-required carry the message.
- **Build our own "rate Cards!" star-rating dialog.** Rejected: the App Store explicitly discourages it, self-built rating sheets erode trust, and the native APIs already handle the hard parts (throttling, dismissal, no-commitment).
- **Don't ask for reviews at all.** Rejected: ASO matters, the target rating was ≥ 4.3, and the native APIs are extremely low-cost / low-risk when gated to positive moments. Not asking would leave organic discovery on the table.

**What changes in the spec:**
- [product-spec.md §2.1](./product/product-spec.md#21-first-session--the-60-second-rule) — "Smart claim prompts fire at meaningful moments" callout removed; replaced with "Claim is opt-in, never pushed."
- [product-spec.md §6.1](./product/product-spec.md#61-anonymous-by-default) — "Smart claim prompts (not gating)" subsection rewritten as "Claim is opt-in (no proactive prompts)" with the rationale and the inline-only surface table.
- [product-spec.md §2.6](./product/product-spec.md#26-app-store-review-prompts) — new section for review-prompt triggers, eligibility gate, never-trigger list.
- v1-mvp.md §1 — "anonymous → claimed conversion" downgraded from a ≥ 20% target to directional-only. (v1-mvp.md has since been deleted; tracked here for the historical record.)
- v1-mvp.md §2.2 + §2.6 — Phase 3 must-haves updated; new §2.6 for review prompts.

**Status:** Locked. The smart-claim-prompts design in the original 6.1 is superseded.

---

## 2026-05-20 — Reject "emojis cost chips" as a chip sink

**Decision:** Table-side emoji blasts stay free. The chip-sink instinct is right; emojis are the wrong lever.

**Why:** Emojis are the social-signal feature that makes the table feel alive. Adding cost suppresses usage, which suppresses the social experience, which suppresses the loss-aversion-on-busts loop that actually drives chip purchases. We'd lose more revenue (and a lot of brand warmth) than the sink would generate.

**Better chip sinks to prefer first:**
- MP buy-in / ante — the natural sink in a poker game.
- Tip the dealer at hand end ([product-spec.md §4.1.5](./product/product-spec.md#41-currency--chips)).
- Profile rename / title change cost.
- Custom avatar slots, name color, name glow, profile decorations (shop catalog §4.3).

**Alternatives considered:**
- **Flat cost per blast.** Rejected as above.
- **Tiered cost (rare emojis cost, common ones free).** Same suppression effect on the common-tier social signal that does the work.

**Revisit when:** the preferred sinks (especially MP buy-in) prove insufficient to keep chips a flowing resource. Default position remains: do not charge for emojis.

**Status:** Locked.

---

## 2026-05-18 — Identity pivot (REVERSED): back to Supabase Auth on the client

**This supersedes the 2026-05-18 "Identity pivot: server-managed device-keyed identity" entry above.** The earlier reversal of the original 2026-05-13 Supabase-Auth design was made on the assumption that "build claim flow ourselves" was a 2–3 day effort. On a more honest re-estimate (Sign in with Apple's email-privacy-relay handling, name-only-on-first-signin trap, server-side JWKS verification, Google Credential Manager flow on Android, account-linking edge cases), it's 5–7 days plus indefinite maintenance of edge cases.

Phase 3.1 (Apple/Google claim flow) was V1 scope (per the v1-mvp.md doc that existed at the time of this decision; the V1 scope frame now lives in [product-spec.md §9](./product/product-spec.md#9-roadmap)), so this is a near-term cost, not a deferred one. Supabase Auth handles all of the above out of the box; `supabase-kt` (already in `libs.versions.toml`) is a first-class KMP client. The right call is to commit.

**The new shape:**

| Concern | Owner |
|---|---|
| Sign in (anonymous, Apple, Google, magic-link, etc.) | Supabase Auth, called via `supabase-kt` directly from the client |
| Token issuance + refresh | Supabase Auth (server-side, transparent to us) |
| Token storage on device | `supabase-kt`'s `SettingsSessionManager` (uses multiplatform-settings) |
| JWT validation on our server | `ktor-server-auth-jwt` configured with `SUPABASE_JWT_SECRET` (HS256) |
| Profile (display name, avatar emoji, future game state) | Our Postgres `profiles` table, FK to Supabase's `auth.users(id)` |
| Profile bootstrap on first sign-in | `GET /v1/me` is get-or-create: if no profile row, generate username + emoji and insert |
| Game logic, chips, XP, achievements, rooms | Our Ktor server (server-authoritative, unchanged) |
| Realtime game state during a hand | Our Ktor WebSockets (server-authoritative, unchanged) |

**What we throw away from the prior server-managed-identity design:**
- `JwtTokenService` + Auth0 java-jwt direct usage for minting
- `refresh_tokens` table (Supabase handles refresh)
- `identities` table (Supabase's `auth.users` replaces it)
- `device_links` table (Supabase has no device-keyed recovery; users either claim or accept the orphan-on-reinstall behavior)
- `POST /v1/identity` route (Supabase Auth replaces it)
- `POST /v1/auth/refresh` route (Supabase Auth replaces it)
- Client `IdentityAuthTokenProvider`, `TokenStoreImpl`, `IdentityApi`, `DeviceIdProvider` Kotlin Twin and its Android/iOS impls

**What we keep:**
- Postgres + Hikari + Exposed + Flyway + Testcontainers scaffolding (still valuable for our own data)
- `UsernameGenerator` + `EmojiAvatarGenerator` (called by `/v1/me` on first miss)
- `profiles` table — schema mostly unchanged; FK now points at `auth.users(id)` instead of our own `identities(id)`
- Server's Ktor structure (plugins, routes, observability, error envelope)
- `:libraries:identity` interface module (the contract stays clean; only the impl swaps)
- Onboarding feature module shell — VM now drives Supabase sign-in instead of `/v1/identity` POST
- Network client lazy-provider cycle fix (still correct for any auth backing)

**Anonymous → claim → sign-in conceptual model:**

Supabase splits these into two distinct operations and we expose both:

1. **Claim (link Apple/Google to current anonymous account):** `supabase.auth.linkIdentity(provider)`. Preserves all data (chips, XP, inventory). Fails if that OAuth identity already belongs to another `auth.users`.
2. **Sign in to existing (switch accounts):** `supabase.auth.signInWithOAuth(provider)` (or `signInWith(IDToken)` for native flows). Switches the session. **Anonymous data is orphaned** (no auto-merge) and eventually cleaned up by a TTL sweep.

V1 UX:
- Primary path: "Claim" button → `linkIdentity` → happy or "this OAuth is already on another account — sign in there? (you'll lose guest progress)" prompt.
- Secondary path: "I already have an account" → `signInWithOAuth` → explicit confirmation about losing guest data.

We do **not** build automatic account-merge logic for V1. Picking the "claim first" default for the common case is enough; users who explicitly switch accounts accept the trade-off.

**Trade-offs we accept by re-adopting Supabase:**

- Vendor lock to Supabase Auth + Postgres. Migration cost down the road = export `auth.users`, write a one-time script to map to a new identity provider, swap `supabase-kt` for whatever replaces it. ~1 week of work if we ever do it. Acceptable for V1.
- Anonymous accounts orphaned on `signInWithOAuth` to a pre-existing account. Sharp edge — V1 acceptable. Document a TTL cleanup task to delete anon-only `auth.users` >30 days inactive.
- Our server validates Supabase JWTs but doesn't talk to Supabase Admin API (yet). Future work might add admin operations (account deletion compliance, user lookup) — needs `SUPABASE_SERVICE_ROLE_KEY` server-side then.

**Required Supabase project configuration (manual steps):**
- Authentication → Settings → "Allow anonymous sign-ins" → **on**.
- Project Settings → API → record JWT secret (server `.env` → `SUPABASE_JWT_SECRET`).
- Project Settings → API → record `anon` public key (client config → `SUPABASE_ANON_KEY`).
- Phase 3.1: Authentication → Providers → enable Apple, Google with the respective OAuth credentials.

**Status:** Locked. The earlier "server-managed device-keyed identity" entry is now historical; reading the log top-to-bottom, the third entry on this topic (this one) is the live decision.

---

---

## 2026-05-18 — V1 client token storage: file-backed cache, not OS-encrypted

**Decision:** The client stores its server-issued JWT access + refresh token pair in the same `:libraries:storage` file-backed cache used for `AppData` (DataStore on Android, file-backed JSON on iOS). **Not** EncryptedSharedPreferences (Android) or Keychain (iOS).

**Why this is acceptable for V1:**
- All identities in V1 are anonymous. A stolen refresh token grants access to a device-bound anonymous account with no PII, no real money, only play chips. The user's recovery path is "reinstall, get a new identity."
- The OS already sandboxes app storage. A non-rooted/jailbroken device with screen-lock is well-defended; a rooted/jailbroken device with tokens in Keychain isn't materially safer than with them in DataStore.
- Android encrypted storage is straightforward; iOS Keychain from Kotlin requires either cinterop boilerplate or a Swift Twin. Landing both alongside the rest of V1 auth wasn't worth the time at this risk level.

**When this becomes unacceptable (and the trade-off resets):**
The moment Apple/Google "claim" lands (Phase 3.1). A claimed account binds to a real human and the refresh token unlocks their persistent state across devices — at that point a leaked token has user-visible consequences.

**Upgrade path:**
- Add `androidx.security:security-crypto` to `:libraries:identity:impl` androidMain deps. Bind an `EncryptedSharedPreferencesTokenStore` with `@ContributesBinding(replaces = [TokenStoreImpl::class])` in the same source set.
- Add an iOS Keychain wrapper. Easiest route: Swift Twin (per `docs/practices/swift-kotlin.md`) — interface stays in commonMain, Swift implements it and passes it into the DI graph via `IosAppComponentFactory.create(...)`. Bind with the same `replaces` annotation in iosMain.
- The interface (`com.dangerfield.cards.libraries.identity.TokenStore`) doesn't change; only the wiring does. Existing on-device tokens get re-written into the new store on the next refresh (or first run after the upgrade).

**Status:** Accepted V1 trade-off. Bump to OS-encrypted storage before the claim flow ships.

---

---

## 2026-05-14 — XP earning formula and local-only persistence (V1)

**Decision:** XP scales with **engagement intensity**, not outcome. The base formula (multiplayer rate, halved for bots) per finished hand is:

| Source | Amount | Condition |
|---|---|---|
| BASE | +10 | every finished hand (even a fold) |
| INVESTMENT | +1 per BB committed, capped at +20 | chips voluntarily put in this hand |
| SHOWDOWN | +10 | reached showdown |
| HAND_STRENGTH | (categoryOrdinal + 1) × 2 (1..20) | hand shown at showdown — winning or losing |

Bots earn 0.5× of every component (per the locked anti-farm rule). Multiplayer earns 1.0×. The `wonPot` flag is **not** an input — winning and losing the same hand at the same engagement level earn identical XP.

**Persistence:** `total_xp` is **server-authoritative as of Phase 3 Slice 1 (2026-06-14)** — Model 2 (optimistic-local + server-reconciled), mirroring the chips wallet. The client still computes XP per hand with `XpCalculator` and accrues it offline; `ProgressionRepositoryImpl.sync()` flushes the `xp_events` ledger to `POST /v1/me/progression/sync` and reconciles the local total to the server's value. So XP now survives reinstall / account switch / cross-device. **Lifetime hand counters** (`progression` singleton: handsPlayed/won/folded/…) are **still client-local** — they reset on a switch and aren't re-hydrated yet (see todo).

**Why this shape:**
- "Scale by hand strength / pot size" (per user) felt better than flat per-hand, but the engagement-intensity framing keeps the decoupling-from-outcome invariant intact.
- Hand-strength bonus at showdown rewards "showing up and showing a real hand" — naturally tracks skill and play depth without rewarding luck.
- Cap on investment (20 BB) prevents one all-in lottery hand from dwarfing a session of solid play.
- Local persistence now (vs. waiting for Phase 3) means the XP detail sheet ships with real, growing numbers; users see progress from day one. Migration to server is a one-shot import once auth lands.

**How to apply:**
- New XP sources must follow the rule: amount may depend on what the player did, never on what the opponent did or who won.
- When tuning numbers (everything in `XpCalculator.kt`), preserve order-of-magnitude — a normal hand should feel like "10-30 XP" against bots and "20-60 XP" in multiplayer.
- Level thresholds remain deferred (per the previous entry) until we have a session's worth of real XP numbers to anchor them.

**Status:** Locked for V1. **Phase 3 Slices 1 + 2 landed (2026-06-14):** `total_xp` (Slice 1) and the achievement *earned set* (Slice 2) are server-authoritative (Model 2); the XP formula + achievement criteria stay client-side and `level` stays derived from `total_xp`. Remaining: graduate the lifetime hand counters + achievement progress counters, and claim-time backfill (Slice 3) — see `todo.md`.

---

---

## 2026-05-14 — Chips, rank, XP are three separate concepts

**Decision:** Cards has three independent progression/value axes. They do not collapse into each other.

1. **Chips** — buy-in currency.
   - **Multiplayer:** persistent, "sacred" (no random refills, no daily free spins). Going broke = rate-limited recovery grant (one-shot, server-enforced) per the V1 plan's bottom-out path.
   - **Bot mode:** practice chips. Auto-rebuy to `startingStack` between hands if the seat busted (already shipped — `LocalBotsSession.lastSeatsForRotation`). No real consequence.

2. **Rank** — Elo-style skill rating, **multiplayer-only**.
   - Bots don't move rank because they're static heuristics — beating Jane 100 times says nothing about your skill vs humans.
   - Floors around 800 (real Elo behavior), can't hit zero.
   - For V1 (bots only), displayed but with a "Play multiplayer to earn rank" hint. Doesn't change.

3. **XP** — lifetime engagement counter, **both modes**.
   - Always goes up. Cannot decrease, cannot bottom out.
   - Bot games earn at **0.5×** the multiplayer rate (per the V1 plan's anti-farm rule).
   - Drives level progression / achievements / cosmetics unlocks (future).
   - This is the "I made progress" signal every session, decoupled from win/lose.

**Why:** Every successful poker app (Offsuit, PokerStars, even Zynga) separates these. Collapsing them — e.g., "rank = chips won" — creates the "I went broke, I'm starting over" experience that kills new-player retention. Three lanes means a beginner can lose chips, see XP go up, see rank stay flat, and still feel like they're moving forward.

**How to apply:**
- Treat any new feature touching one axis as not touching the others. A chip refill doesn't affect XP. An XP bonus doesn't move rank. Etc.
- When rendering profile/home: show all three, never merge into one summary metric.
- For Phase 3 persistence: the `xp_events` ledger from the V1 plan covers XP. Chips and rank go in their own server-authoritative tables.
- For V1, surface XP as a number; level/progress-bar UI lands when we have enough data to know what XP thresholds feel right.

**Status:** Locked for V1.

---

---

## 2026-05-13 — Client/server boundary: server-first, auth is the only exception

**Decision:** The mobile client talks directly to **Supabase Auth** for the Apple/Google sign-in flow and that's it. Everything else — profile, leaderboards, room create/join, game state, chips, XP, connections, AppConfig, the future hand history and notifications register — goes through the Kotlin Ktor server. The server is the only thing that talks to Postgres.

The split, concretely:

| Concern | Path |
|---|---|
| Sign in with Apple / Google | Client → Supabase Auth (direct, via the OS OAuth flow) |
| JWT validation | Server validates the Supabase JWT on every HTTPS request and every WS connect |
| Profile read/write, leaderboards, rooms, XP, connections, app config | Client → Ktor server (HTTPS, JWT-authenticated) |
| Realtime game state during a hand | Client ↔ Ktor WebSocket (one channel per room) |
| Postgres queries | Server only, via direct DB connection with the service role key |
| Supabase Realtime | Not used in V1. Possible future use for low-stakes row subscriptions (e.g. "friend started a game") but never for in-hand game state. |

**Why server-first:**

1. **Poker forces it.** Shuffle, deal, betting validation, hand evaluation must be server-authoritative. Half the code already goes through the server — making the rest match removes the split brain.
2. **Schema changes don't break clients.** When a column is added or renamed, the server adapts the response shape; old binaries keep working. Direct-to-Supabase welds each client version to its schema version, which is painful with App Store / Play Store update lag.
3. **Business logic stays in one place.** "Award XP on hand completion" touches multiple tables and must be atomic. One Ktor transaction is bulletproof; three Supabase calls from a phone are fragile (network drops, partial writes).
4. **Anti-abuse and provably-fair primitives need server enforcement.** Rate limiting, intent nonces, the shuffle commit-reveal protocol, turn-timer enforcement — none of these can be done with RLS alone.
5. **Migration optionality.** If we ever outgrow Supabase, swapping the server's DB driver is one PR. Direct-to-Supabase means every shipped client has `supabase.co` welded in.

**Why realtime through Ktor, not Supabase Realtime:**

Supabase Realtime broadcasts row changes. The game state during a hand lives in an in-memory coroutine on the server, not in a Postgres row — persisting every state transition just to fan it out would be wasteful and would expose intermediate states (the moment hole cards are dealt, they'd briefly land in a row before any RLS could hide them). Server-driven turn timers need code, not row triggers. Ktor WebSockets give us a per-room channel where the server publishes JSON diffs when it wants to. Standard pattern.

**Supabase's role in this architecture:**

We're using Supabase for:
- Managed Postgres (hosted DB, point-in-time recovery, backups)
- Auth (JWT issuer + Apple/Google OAuth dance)
- Maybe Storage later for avatar uploads

We're not using:
- PostgREST (the auto-generated REST API)
- Supabase SDK on the server (we connect to Postgres directly)
- Realtime (we have our own WS)

This makes Supabase feel like "managed Postgres + hosted auth" rather than "all-in-one backend," which is the right framing for an app with its own game-logic server.

**How to apply:**

- When adding a new client capability, the default answer is "add a Ktor endpoint" not "query Supabase directly from the client."
- The one exception is the Sign-in-with-Apple / Google flow, which has to happen client-side because Apple/Google's OAuth UI runs on-device.
- New realtime features inside a room (emotes, chat, sit-out signals) go through the existing per-room WS channel, not a new Supabase subscription.
- Realtime features *outside* a room (notifications about friends, leaderboard ticks) can use Supabase Realtime if it's the simpler answer, but evaluate per case.

**Status:** Locked.

---

---

## 2026-05-13 — "Sacred chips" principle

**Decision:** Going broke is a real consequence. No random refills, no daily login bonuses, no free spins. Bottom-out path: claimed users can request a one-time recovery grant if balance hits zero, server-rate-limited (e.g. once per 24h, decaying amount). Anonymous users get their initial float and that's it until they claim.

**Why:** Borrowed from Offsuit reviewer feedback ("chips feel sacred" cited as a positive). Reinforces seriousness of the game without monetization gates.

**Status:** Locked for V1.


---

## 2026-06-24 — Bounded room-socket reconnect on a healthy-session signal

**Decision:** `ReconnectingRoomSocket` only resets its reconnect-attempt counter once a connected session is *healthy* — defined as having delivered at least one decodable frame. A session that completes the WS handshake but drops before delivering any frame no longer resets the counter, so repeated instant drops climb the exponential backoff. After `MAX_RECONNECT_ATTEMPTS` (6) consecutive frame-less drops the socket gives up with a new terminal `ClosedReason.ReconnectFailed` instead of looping forever.

**Why:** A half-open server socket (left behind after the sole other human leaves a 2-player room) accepts the handshake and immediately drops it. The old loop reset `attempt` on every handshake success, so it spun connect→drop→reconnect at the 250ms floor with `attempt` stuck at 1 — an unbounded storm the user could only escape by mashing Back (CARDS-37). "Delivered a frame" is a clean, test-friendly health signal: a half-open socket delivers nothing, while a genuinely-working connection that blips once after minutes of play still resets fairly.

**Alternatives considered:** (1) Time-based health (session up ≥ N ms) — needs an injected clock and is harder to virtualize in the existing `StandardTestDispatcher` tests. (2) Capping the handshake-retry path too — left out to preserve the existing `consecutiveFailures_incrementAttemptCounter` contract and because the reported failure is the connected-then-dropped path, not 5xx handshakes. Deferred as a follow-up.

**Status:** Shipped.


---

## 2026-06-24 — Reconcile the wallet on leaving a real-chip MP table (MP-7)

**Decision:** `PlayPokerViewModel` calls `chipsRepository.sync()` immediately after `session.leave()` whenever the table is a real-chip multiplayer table (`XpMode.MULTIPLAYER`). Both leave paths (`LeaveTable`, `LeaveGameFromBust`) route through one `leaveAndReconcileWallet()` helper on `appScope` so the sync outlives the screen pop. Solo/bots practice tables skip the sync (no escrow moves).

**Why:** The server already cashes a leaver's final table stack back to the wallet on leave (`DefaultTableSessionService.cashOut`, keyed/idempotent — proven by `ChipEconomyPlayTest`). The bug was purely client-side reflection: the local wallet is a write-through cache that only hydrates on cold boot / warm foreground, so a player who won a pot and left saw their balance unchanged until the next foreground, when a partial resync surfaced a confusing phantom delta (CARDS-3C: "won 500, wallet unchanged, then +100 later"). Forcing a sync on leave lands the credited stack right away.

**Alternatives considered:** (1) Route the server's `CashedOut(refunded, balanceAfter)` to the client over the socket and apply it optimistically — richer (enables a credited-amount toast, MP-6 part 1) but needs new protocol plumbing on a fan-out leave path with no request/response tie; deferred. (2) Push a server-authoritative balance frame on every leave — same plumbing cost. The sync-on-leave is the minimal correct fix; the existing single-flight mutex on `sync()` collapses any overlap with the foreground resync.

**Status:** Shipped.

## 2026-06-25 — Server-authoritative player stats, streak carried as a snapshot (PROG-1)

**Decision:** Hand counters (played / won / folded / lost-at-showdown / bot hands), the no-bust streak (current + best), and the per-bot win map graduate to the server as a `user_player_stats` aggregate plus an append-only `player_stat_events` ledger keyed `(user_id, idempotency_key)` — the same Model-2 shape as play_style (V69) and wallets (V6). `GET /v1/me/player-stats` reads the snapshot; `POST /v1/me/player-stats/sync` flushes a batch of per-hand events the server folds idempotently (the `player-stats` namespace deconflicts from the pre-existing `/v1/me/stats` lifetime-opponents read). The summable counters and the per-bot map accumulate; the **streak is carried as a snapshot** on each event (the client's running no-bust streak after that hand), and the aggregate takes the latest applied value as `current_no_bust_streak` and the running max as `best_no_bust_streak`.

**Why:** Stats were device-only, so account-switch / reinstall reset them and the stats screen + achievement progress bars read wrong on a second device. Making stats the source of truth lets achievements become predicates over them — a new achievement points at an existing counter with no data migration. Streaks are order-dependent, so they can't be re-derived by summing a ledger the way the counters can; sending the client's post-hand streak value and folding it latest-current / max-best keeps the ledger idempotent without the server replaying hand order.

**Alternatives considered:** (1) Recompute the streak server-side from the ordered ledger — correct but forces the server to read+replay the whole event history on each sync and makes a mid-batch replay non-trivial; rejected for the snapshot fold. (2) Store per-bot wins as their own ledger/table rather than a JSONB map on the aggregate — heavier for a handful of keys; the map mirrors how small per-key state rides on an aggregate row elsewhere.

**Status:** Server slice shipped (table + migration V72 + domain/Postgres repo + DTO + routes + DI + delete cascade + tests). Client half (write-ahead-cache repo mirroring `PlayStyleRepositoryImpl`, then re-point the stats screen + achievement predicates) remains under PROG-1.


## 2026-06-25 — Persist last-known stacks in the session snapshot, not the table_sessions row (MP-13)

**Decision:** The per-player `lastKnownStacks` map a `GameSession` keeps (each player's stack as of the last hand they were seated for, retained after they bust + are dropped) is now persisted in the `room_sessions` snapshot — a new `last_known_stacks_jsonb` column (V74) carried on `SessionSnapshot` alongside the serialized `GameState`. The boot recovery sweep (`DefaultTableSessionRecoverySweep`) reads the live seat's stack first, then falls back to this persisted map before refunding the full escrow, so a busted-and-dropped player swept after a crash is cashed out their real 0 rather than minted their whole stake.

**Why:** The live-leave mint was already fixed in-memory via `GameSession.lastKnownStack`, but that map died with the process. After a crash the sweep rehydrated only the snapshot's `GameState`, which has no seat for a busted-dropped player, and fell through to a full-escrow refund — the same mint, one path over. The snapshot is the natural home: it is already written per-mutation inside the per-session mutex, already hydrated on restart, and the sweep already reads it via `snapshots.readByCode`. Co-locating the last-known stacks with the state means one durable write keeps both in step and the sweep needs no second lookup.

**Alternatives considered:** (1) Persist the last-known stack on the `table_sessions` row instead. Rejected: that row is per-user lifecycle bookkeeping written on sit-down / status flips, not on every hand boundary, so it would need a new write path on the gameplay hot loop and a second source of truth for "what did this player walk away with"; the snapshot already mutates at exactly the right cadence. (2) Recompute the stack from the snapshot's event history — there is no durable event log (snapshot-only state, see 2026-05-29), so nothing to replay. Round-trip safety: the column is `NOT NULL DEFAULT '{}'`, so pre-V74 rows and any insert that omits it read back as an empty map (no recorded stack → the sweep falls back to a full refund, the prior behaviour) rather than failing to deserialize.

**Status:** Shipped. Red/green proven by `Mp13CrashRecoveryConservationTest` (harness `restart()` + sweep); `PostgresSessionSnapshotStoreTest` pins the round-trip + the pre-V74 empty-map fallback.

## 2026-06-26 — Lazily seed a config flag from its manifest default when a rule first attaches (ENG-4)

**Decision:** The admin rule write (`PUT /v1/admin/config/rules/{id}`) now calls `seedFlagFromManifestIfMissing` before the rule upsert: if the flag has no DB row, the server materializes one from the flag's shipped manifest default and only then attaches the rule. A flag with neither a DB row nor a manifest entry still returns 409 `unknown_flag`. The admin client no longer mints a base override (`upsertFlag(seed)`) as a side effect of adding a rule, and `launchOp` now reloads on both success and failure so a rejected write can't leave the flag list looking stale.

**Why:** The targeting-rule write has an FK on the flag row. The client worked around it by writing a DB base override from the in-code default before every rule add, so a failed rule-add could leave behind a base override the operator never intended, and the resolve layers showed a "base value" the operator never set. Moving the seed server-side, sourced from the authoritative manifest default rather than a client guess, keeps the FK invariant intact while making "add a rule to a flag that only ships in code" a single honest operation. Reloading on failure fixes the separate no-render bug where a 400/409 set an error banner but skipped the refresh, so the just-added rule looked like it silently vanished.

**Alternatives considered:** (1) Relax the FK so rules can reference a flag with no row. Rejected: it splits the source of truth (a rule pointing at a non-existent flag) and complicates resolve, which already unions DB + manifest. (2) Keep minting the base override but do it server-side. Rejected: that still presents the shipped default as an operator-set "base value", which is exactly the confusion ENG-5 is chartered to remove; seeding silently from the manifest default (an audit `create_flag` row records it) is the honest middle ground.

**Status:** Shipped. Red/green proven by `ConfigAdminRoutesTest.upsertRule_forManifestOnlyFlag_seedsBaseFromManifestDefault_andSucceeds` (was 409, now 200 + seeded base) and `upsertRule_forUnknownFlagWithNoManifest_is409` (the no-manifest path stays an honest conflict).

## 2026-06-27 — Rewrite Terms/Privacy to professional coverage; 18+ age gate, arbitration + class-action waiver (AUTH-7)

**Decision:** Rewrote `pages/terms.html` and `pages/privacy.html` from the thin starter set to the full coverage a simulated-gambling app is expected to carry, in Dealt's plain-English voice. Terms now cover: amusement/non-gambling disclaimer, an **18+ age gate**, virtual-currency disclaimer, app-store terms (Apple as third-party beneficiary), third-party services (Supabase/Fly/Sentry named), suspension/termination + survival, warranty disclaimer, limitation of liability, indemnification, a **binding-arbitration + class-action-waiver** dispute-resolution block (AAA Consumer Rules, NY seat, small-claims + IP carve-outs, **30-day opt-out**, one-year limit), NY governing law, and severability/entire-agreement. Privacy adds: a sub-processor disclosure ("Where your data lives" — Supabase + Fly, US), retention, security, and a "Your privacy rights" section (access/correct/delete/export/object + regulator complaint). Children raised from under-13 to under-18 to match the age gate. `LegalUrls.LEGAL_VERSION` bumped `1 → 2` so the re-accept gate fires.

**Why:** The owner asked to match competitor (Offsuit) coverage. We deliberately did **not** copy Offsuit's text — it's copyrighted and describes their entity, practices, and third parties (ads, offer walls, real-money, social-login friend import) that Dealt doesn't have, which would make Dealt's docs factually false. Instead we wrote Dealt-accurate prose covering the same professional topics, and dropped the inapplicable Offsuit clauses (advertising/offer-wall, subscriptions, multi-state privacy appendix). 18+ chosen over the current 13 because the app carries a poker theme and a "simulated gambling" store rating; aligning the age gate sidesteps COPPA/teen-data scope.

**Alternatives considered:** (1) Copy Offsuit verbatim — rejected (copyright + factual inaccuracy, above). (2) Keep the NY-courts-only model — owner chose to add arbitration. (3) Keep 13+ — out of step with the poker rating.

**Caveat / follow-up:** The arbitration + class-action-waiver clause is the most legally consequential part and its enforceability turns on drafting; this is a reasonable standard version, **not** a substitute for counsel. A lawyer review before launch is tracked in `developer-todo.md`.

**Status:** Shipped (docs + version bump). No automated test — static legal copy; `LEGAL_VERSION` consumers all read the constant, so the onboarding re-consent tests stay green.
