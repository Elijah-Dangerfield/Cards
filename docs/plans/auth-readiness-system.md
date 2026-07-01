# Plan — Unified auth-readiness system

**Status:** Approved direction, not yet implemented. Build in the staged PRs below.
**Owner of the decision:** captured in `docs/decisions.md` (2026-07-01). This doc is the *how*.
**Audience:** the agent/engineer implementing it. Read the whole thing before the first commit — the module seams (§3) are the load-bearing part; get them wrong and it turns into boilerplate.

---

## 1. The problem we're solving

Today "can this user do this, and if not what do we honestly tell them?" is decided in **two disconnected places that can (and do) disagree**, and a third path fires doomed requests:

1. **Proactive, navigation-time** — [`RealAuthGateChecker.reasonFor`](../../libraries/navigation/impl/src/commonMain/kotlin/com/cards/libraries/navigation/impl/RealAuthGateChecker.kt) combines auth state + guest-creation state + connectivity into a `GateReason` (Offline / NeedAccount / NeedClaimedAccount / FinishingSetup). This is the *good* logic — it's what the recent "you're offline, your progress is safe" fix landed in. But it only fires when you **navigate to** a gated route.

2. **Reactive, mid-screen** — when an action is a button on an already-open screen (e.g. "Create Room"), no nav gate fires. The call goes through [`authedCall`](../../libraries/networking/src/commonMain/kotlin/com/cards/libraries/networking/NetworkCall.kt), whose `awaitAuthReady()` waits only for the auth subsystem to **initialize**, not for a session to **exist**. With no session it fires **unauthed → 401**, and each ViewModel re-derives offline-vs-account from an ad-hoc `isFallbackProfile` heuristic (see `LobbyViewModel`). This is the drift: the reactive path reinvents — and can contradict — the proactive path's answer, and it pollutes telemetry with phantom 401s.

3. **Server-driven block** — [`AccessDeniedBus`](../../libraries/networking/src/commonMain/kotlin/com/cards/libraries/networking/AccessDeniedBus.kt) (banned/suspended via a 403 envelope) routes to a blocking screen. Separate mechanism again.

**Goal:** one background authority that knows everything needed to classify auth-readiness, one vocabulary every layer speaks, and a call boundary that hands callers a *typed* failure they can map, ignore, or route on — with **near-zero per-call-site boilerplate**. Not a wrapper everyone has to remember to use correctly; a system where the default behavior is already right.

---

## 2. Principles (these are the rules, not suggestions)

1. **One vocabulary.** A single `AuthReason` enum, produced by a single evaluator, consumed by nav, the call boundary, and the UI. `GateReason` collapses into it. No parallel enums.
2. **The error is data; routing is a decision.** A failure carries *enough to render any surface*. Whether to show an inline message, a sheet, or a full-screen takeover — and whether to navigate at all — is chosen by the **consumer that has the context**, never automatically because an error happened to propagate. (This is the hard "no" on auto-navigate-on-propagation — see §6.)
3. **Reuse the monad we already have.** `authedCall` keeps returning `Catching<T>`. Auth-unreadiness is a typed failure alongside timeouts and 5xxs. No new result wrapper, no new intent enum. Intent is expressed by *how the caller handles the `Catching`* — that's idiomatic here.
4. **Right-by-default, boilerplate-free.** `authedCall` defaults to `AuthRequirement.Account`; 95% of call sites don't change their signature and automatically get short-circuit + typed failures. Only the rare `ClaimedAccount` call passes an argument.
5. **Reuse the heal machinery, don't reinvent it.** Heal-on-demand kicks the existing `GuestSessionHealer` fire-and-forget. No new minting logic.
6. **Respect the existing module boundaries.** `:libraries:networking` must not gain a compile dependency on `:libraries:identity` or `:libraries:navigation`. We extend the same interface-low / impl-high seam the codebase already uses for `AuthTokenProvider` and `AuthGateChecker` (§3).

---

## 3. The system — components and module seams

The seams are the whole ballgame. The pattern already in the codebase: **define the abstraction in the lowest shared module, bind the implementation from a module that can see everything.** We follow it exactly.

```
:libraries:core   (the shared floor — everyone already depends on it)
   ├── AuthRequirement   enum   ← MOVED here from :libraries:navigation
   ├── AuthReason        enum   (Offline, NeedAccount, NeedClaimedAccount, SessionExpired, FinishingSetup)
   ├── AuthVerdict       sealed (Ready | Blocked(AuthReason))
   ├── AuthGate          interface { fun verdict(requirement): AuthVerdict }
   ├── AuthUnready       exception(val reason: AuthReason)   : Exception
   └── Catching<T>.mapAuthFailure / onAuthFailure / recoverAuthFailure   operators

:libraries:identity:impl   (sees auth state + creation state + connectivity → can implement the brain)
   └── AuthGateImpl : AuthGate, AutoInit
         - caches AuthState + AccountCreationState from their flows (same as RealAuthGateChecker does today)
         - reads AppState.isOffline
         - verdict() is a synchronous peek over cached state (see §3.1)
         - when the state is "healable", fire-and-forget kicks GuestSessionHealer.heal("authGate")

:libraries:networking   (depends on core only — NOT identity)
   └── authedCall / authedWebSocketSession call NetworkClient.authVerdict(requirement)
       (NetworkClient already brokers auth access via awaitAuthReady(); we add one more broker method)

:libraries:navigation   (depends on core)
   └── RealAuthGateChecker becomes a THIN ADAPTER: asks AuthGate.verdict(), maps AuthVerdict → AuthGateRoute
       GateReason is deleted; AuthGateRoute carries AuthReason directly

apps/compose   (the UI)
   └── BlockReason sealed union { Auth(AuthReason) | AccessDenied(...) }  feeds one blocking presentation
       AuthGateSheet renders any AuthReason from one copy table (already ~does this for GateReason)
```

### 3.1 Why `verdict()` is synchronous, and how heal-on-demand works without blocking

The nav path (`AuthGateChecker.gate`) must stay non-suspending — the router calls it inline. So `AuthGateImpl` keeps the existing trick: it's an `AutoInit` that eagerly caches `AuthState` and `AccountCreationState` from their flows, so `verdict()` is a cheap peek over cached state, usable from both the sync nav path and the suspend call path.

Heal-on-demand does **not** make `verdict()` block. When `verdict()` finds a *healable* state (onboarded + online + session-less + reason `None` + not a deliberate sign-out), it:
- returns `Blocked(FinishingSetup)` **now** (honest: "we're finishing setup"), and
- fire-and-forget calls `guestSessionHealer.heal("authGate")`.

The heal runs in the background; the caller reports honestly *now*; the retry (user taps again, or the `UserChanged` identity fan-out re-fires background syncs) succeeds. This is consistent with how `ifAuthenticated {}` already re-fires on identity change. **No bounded wait, no hang risk.** This is deliberately simpler than an "ensureAuthed() that waits" — the passive healer already covers the common reconnect case; this only exists to unstick the narrow "online + stranded + no event re-fired" state.

### 3.2 The verdict decision table (single source of truth)

`AuthGateImpl.verdict(requirement)` — the *only* place this logic exists after this lands:

```
given the cached auth state, creation state, requirement, and AppState.isOffline:

  Authenticated + requirement met (Account, or ClaimedAccount && !isAnonymous)  -> Ready
  Authenticated but requirement == ClaimedAccount && isAnonymous                -> Blocked(NeedClaimedAccount)

  // NOTE: an Authenticated user is ALWAYS Ready for Account calls, even offline.
  // We hold a (possibly stale) session; let the request try and fail at transport
  // as an ordinary network error — do NOT route it through the gate. This preserves
  // the "authenticated guest, offline, cached JWT" path untouched.

  not Authenticated:
    creationState is InProgress || Failed        -> Blocked(FinishingSetup)   // it heals
    healable (onboarded, online, reason None,
              not signed out)                    -> kick healer; Blocked(FinishingSetup)
    isOffline                                    -> Blocked(Offline)          // can't confirm — not "no account"
    reason == SessionExpired                     -> Blocked(SessionExpired)
    else                                         -> Blocked(NeedAccount)
```

`authedCall` maps `Ready -> fire the request`; `Blocked(reason) -> Catching.failure(AuthUnready(reason))` **without firing**. Doomed offline/no-account calls never hit the wire.

---

## 4. Call-site ergonomics — how callers handle or ignore

The point is that a caller declares intent by *how it consumes the `Catching`*, using a tiny set of shared operators (all in `:libraries:core`, next to `Catching`).

```kotlin
// (a) USER ACTION — surface the honest message. One shared operator, no isFallbackProfile.
networkClient.authedCall("room.create") { client -> client.post(...) }
    .mapAuthFailure { reason -> reason.toCreateRoomOutcome() }   // Offline -> Offline, NeedAccount -> NeedAccount, ...

// (b) BACKGROUND SYNC — don't care; drop anything that fails (== today's ifAuthenticated skip)
networkClient.authedCall("wallet.sync") { ... }.getOrNull()

// (c) READ WITH FALLBACK — serve cache on any failure
networkClient.authedCall("profile.fetch") { ... }.getOrElse { Profile.Fallback(id) }

// (d) USER ACTION THAT WANTS A TAKEOVER — the consumer *decides* to route (never automatic)
networkClient.authedCall("room.join") { ... }
    .onAuthFailure { reason -> sendEvent(NavigateToBlocking(BlockReason.Auth(reason))) }
```

The operators (keep this set minimal — resist growing it):

```kotlin
// map an auth-unready failure to a value/outcome; leaves real failures (timeouts, 5xx) untouched
inline fun <T> Catching<T>.mapAuthFailure(map: (AuthReason) -> T): Catching<T>
// side-effect on an auth-unready failure (e.g. emit a nav event); returns the Catching unchanged
inline fun <T> Catching<T>.onAuthFailure(action: (AuthReason) -> Unit): Catching<T>
// recover an auth-unready failure into a success value; passthrough otherwise
inline fun <T> Catching<T>.recoverAuthFailure(recover: (AuthReason) -> T): Catching<T>
```

**Boilerplate budget:** default requirement means most calls are unchanged. "Ignore" is one existing operator. "Handle" is one shared operator with a `when(reason)`. There is no per-call ceremony to remember — a call that does nothing special still gets short-circuit + typed failure for free.

---

## 5. Staged implementation (each stage is one shippable, reversible PR)

Build the whole design; stage only the *migration*. Every stage compiles and is independently revertible.

**Stage 1 — Vocabulary + evaluator, no behavior change.**
- Move `AuthRequirement` from `:libraries:navigation` to `:libraries:core` (mechanical; update imports). It's now universal vocabulary, not nav-specific.
- Add `AuthReason`, `AuthVerdict`, `AuthGate` (interface) to `:libraries:core`.
- Add `AuthGateImpl` in `:libraries:identity:impl` with the §3.2 table, caching flows exactly as `RealAuthGateChecker` does today. `@ContributesBinding(AppScope)` + `AutoInit` multibinding.
- Point `RealAuthGateChecker` at `AuthGate` — delete its private `reasonFor`; `AuthGateRoute` carries `AuthReason` (delete `GateReason`, update `AuthGateSheet`'s `when`). **Pure refactor: identical nav behavior, now sourced from the shared brain.** This is the safe first commit.

**Stage 2 — Typed failures at the call boundary.**
- Add `AuthUnready` exception + the three `Catching` operators to `:libraries:core`.
- Add `NetworkClient.authVerdict(requirement)` (broker method, mirrors `awaitAuthReady()`); bind it to `AuthGate` in `:libraries:networking:impl`.
- `authedCall(description, requirement = AuthRequirement.Account, retry, block)`: consult verdict; `Blocked -> Catching.failure(AuthUnready(reason))` without firing; `Ready -> existing path`. Same for `authedWebSocketSession`. Keep `awaitAuthReady()` for the `Ready` path (it still matters for the slow-bootstrap timeout reason documented in `NetworkCall.kt`).
- No call site changes yet — existing callers just start getting `AuthUnready` instead of a 401 in the session-less case. Verify nothing regresses (they already treat it as a failure).

**Stage 3 — Migrate user-initiated actions; delete the heuristics.**
- Convert create/join room, IAP, and any other user-facing authed action to `mapAuthFailure` / `onAuthFailure`.
- **Delete `isFallbackProfile`** and every ad-hoc offline-vs-account branch it fed. This is the payoff stage — the divergence is now structurally impossible.
- Convert obvious fire-and-forget syncs to the `.getOrNull()` form where it reads cleaner (optional; `ifAuthenticated {}` remains valid — see §6).

**Stage 4 — Unify the blocking presentation.**
- Add `BlockReason` union (`Auth(AuthReason) | AccessDenied(...)`) in `apps/compose`; one blocking surface switches on it. Route `AccessDeniedBus` and any VM-initiated `NavigateToBlocking` through it.
- Keep presentation *weight* per reason (recoverable auth reasons → the existing sheet; `AccessDenied` → full-screen). Unify the *data*, not necessarily the widget. See §6 open decision.

**Stage 5 — Cleanup.**
- Fix the stale top-of-file doc on [`AuthRepository`](../../libraries/identity/src/commonMain/kotlin/com/cards/libraries/identity/auth/AuthRepository.kt) ("on construction… signs in anonymously") — it contradicts the current no-auto-mint behavior.
- Grep for any remaining bespoke "not signed in vs offline" copy decisions and route them through `AuthReason`.

---

## 6. Hard rules and non-goals (read before you "improve" the design)

- **No auto-navigate on propagation.** An error reaching a consumer is *not* a signal to show a blocking screen. "It got propagated to me" ≠ "take over the screen." The same `AuthReason.Offline` is silent for a background sync, inline for a button tap, and a sheet for a whole gated screen — the *consumer* picks. If you find yourself making `authedCall` navigate, stop.
- **`AccessDenied` is a sibling axis, not an `AuthReason`.** "We can't confirm you" (auth-unready) and "we confirmed you and you're blocked" (banned/suspended) have different causes, recovery, and urgency. They share the `BlockReason` *presentation union* only. Do not flatten them into one enum.
- **Authenticated + offline stays a transport failure.** Do not route an authenticated user's offline call through the gate (§3.2 note). They have a session; let it fail as an ordinary network error.
- **`ifAuthenticated {}` is not deprecated.** It's the correct pattern for fire-and-forget authed syncs that should *skip* (and re-fire on `UserChanged`) rather than surface anything. Stage 3 doesn't have to rewrite them; `authedCall(...).getOrNull()` is an equivalent option, not a mandate.
- **Networking must not compile-depend on identity or navigation.** If a stage seems to require it, the type is in the wrong module — push it down to `:libraries:core`.
- **Don't add a fourth operator** to §4 without a real call site that needs it. The small set is a feature.

---

## 7. Test plan (test-first, per `docs/practices/testing.md`)

- **`AuthGateImpl` verdict table** — pure unit tests over the §3.2 matrix: each (auth state × creation state × requirement × offline) → expected `AuthVerdict`. This is the highest-value test; the whole system's correctness funnels through it.
- **Heal-on-demand** — healable state → `verdict()` returns `Blocked(FinishingSetup)` AND kicks the healer exactly once; non-healable states never kick. Assert `verdict()` never blocks.
- **`authedCall` short-circuit** — `Blocked` verdict → returns `AuthUnready(reason)` and **never invokes `block`** (no wire hit); `Ready` → invokes `block`. Regression guard against phantom 401s.
- **Operators** — `mapAuthFailure` maps `AuthUnready`, passes real failures through untouched; same for the other two.
- **Nav parity** — `RealAuthGateChecker` produces the same routes post-refactor as pre- (golden test over the reason table) so Stage 1 is provably behavior-preserving.
- **Scenario harness** (`docs/agent` integration tests): "cold-boot offline as owed guest → tap Create → Offline surface, zero 401s fired; regain connectivity → heal fires → retry succeeds." Red before green.
- **No-mint-from-background** — a background sync failing with `AuthUnready` must not trigger a guest mint. Guard test.

---

## 8. Open decisions for the implementer (make the call, note it in the PR)

1. **Blocking widget: one or two?** Recommendation: unify the *data* (`BlockReason`) now, keep two *weights* (sheet for recoverable auth reasons, full-screen for `AccessDenied`). Merge into a single widget only if the weights genuinely converge — don't force it.
2. **Does `authedCall` take `requirement` or infer it?** Recommendation: explicit param with `= AuthRequirement.Account` default. Inferring from the endpoint is magic that fights a big team. Keep it visible, keep it defaulted.
3. **Where do the `Catching` auth operators live** if `Catching` and `AuthReason` end up in different sub-packages of `:libraries:core`? Keep them next to `Catching` so they're discoverable by anyone already using it.

---

## 9. Definition of done

- One evaluator (`AuthGateImpl`) is the sole producer of auth-readiness; `reasonFor` and `isFallbackProfile` are deleted.
- `authedCall` never fires a doomed unauthed request; session-less failures are typed `AuthUnready`.
- Nav gate, call boundary, and blocking UI all speak `AuthReason`.
- Adding a new authed call requires zero auth ceremony to be correct by default; handling a specific reason is one shared operator.
- Phantom 401s from session-less calls disappear from Sentry/Tempo.
- The §7 tests are green, including the offline-cold-boot scenario.
