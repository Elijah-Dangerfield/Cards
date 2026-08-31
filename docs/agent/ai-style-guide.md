# AI style guide

Short, scannable "do this / not that" tips distilled from real janitor cleanups, so future
agents stop repeating the same mistakes. Keep it a skim-in-under-a-minute checklist — one line
per tip, imperative, grouped. Tighten or merge before growing. Read alongside `AGENTS.md`.

## Logging & telemetry
- Never log per-recomposition, per-flow-emission, per-engine-tick, or per-bot-iteration — it floods Sentry/Loki/Tempo. Log once per hand / user action / state transition.
- Delete leftover debug scaffolding before shipping (tagged investigation logs like `[some-bug-tag]`, hash-code dumps, "does this fire?" probes). It's not telemetry.
- Don't caption a log line with why it's at that level (`// Info: explains "the room disappeared."`) — the message already says it.

## Comments
- Keep only genuine WHY (a gotcha, a regression it guards, "why this way not the obvious way"). Delete narration that restates the next line.
- Don't delete a substantive WHY just because it's long — over-trimming good context is worse than leaving it.
- Fix KDoc that describes code that no longer exists (renamed actions, removed flows, "used to render here" history) — a wrong doc misleads worse than none.
- Re-check docs that claim something is unused ("not called anywhere yet", "reserved for a later phase") — they rot the moment it gets wired. Grep for callers before believing one.
- Check numbers quoted in comments against the constant they describe (an alphabet documented as 32 chars was 31).

## Compose & previews
- Import `Preview` from `org.jetbrains.compose.ui.tooling.preview.Preview` — never write the fully-qualified `@org.jetbrains…Preview` inline.
- Import symbols; don't inline a fully-qualified reference (`PreviewContent`, `KLog`, `Clock`, achievement registries). One import reads far better than an FQN at the callsite.
- Every public screen-level / component composable has at least one `@Preview` covering meaningful states, not just happy path.
- Don't preview states the producer can never emit (an `isAuthing` spinner nothing sets) — a preview of an unreachable state pins a lie and hides the dead field.

## Dead code
- Delete unused private helpers as you touch a file. A `default…()`/factory with no caller is dead, not "kept for later."
- Production code reachable only from its own tests is dead — flag it (its tests may be masking that nothing else uses it).
- Delete unused imports as you touch a file. A copy-pasted import can *look* used but isn't — a named argument `label = x` does not use an imported `label` symbol.
- A sealed/enum case mapped in the UI but never *emitted* is dead — check the producer, not just the consumer (a rendered `…ComingSoon` no VM sets). Drop the case, its mapping, and its string.
- A branch a prior guard already covers is dead — `if (!canSubmit) return` then `if (pw != confirm) …` never fires when `canSubmit` requires the match. Drop the re-check (and any error variant only it set).
- When a screen loses an affordance, sweep its VM — the lobby kept a whole join-form state machine (action, derived flags, error variants, length constants) after the form UI was deleted. Its tests kept it looking alive.
- `internal` helpers in a shared `*Common`/util file go dead silently when the only screen that called them is cut — grep the whole module (not just the file) before trusting an `internal fun` is live (placeholder seat lists + a read-only stake card outlived their deleted public-rooms shells).
- Dead strings hide in `strings.xml` — grep with word boundaries before trusting "used" (`…_start_button` matched only `…_start_button_waiting`).
- A VM action no screen dispatches is dead API (`DismissError` with no dismiss affordance) — grep for the *dispatch* (`onAction(…)`), not the handler; its own tests keep it looking alive.
- `if (!flag) Chrome()` is dead when every path into that screen sets the flag — trace the flag's producers (back buttons on steps only reachable after `creationStarted = true` never rendered).

## State & lifecycle
- Consume a queue/buffer only on the path that succeeded — clearing it before the operation can still be refused silently drops the work nothing will re-queue.
- If a read path falls back to durable storage on a cache miss, every entry point that can be *first to touch* the key needs the same fallback — `find` hydrating but `join` 404-ing is a restart bug.
- Record an idempotency nonce only once the mutation is committed; burning it on a refused path makes the client's retry a silent no-op.

## Naming & clarity
- Don't shadow an outer `val` with an inner one of the same name — rename the inner (e.g. `previousHumans` → `priorHumans`) so each read is unambiguous.

## Wiring & testability
- Never ship an empty-lambda callback stub (`onClaimAccount = {}`) at an entry point — it renders a dead button. Wire it, or leave a WHY comment if it truly can't be wired yet.
- Never ship preview/sample data inside a production composable (a hardcoded 🦊 avatar where the user's own belongs) — wire the real repository; canned identity lives in `@Preview` helpers only.
- Don't smuggle `updateState` past the SEA invariant via a no-op "carrier" action — a helper only called from a handler should be an extension on the action (`private suspend fun MyAction.resetToIdle()`).
- Pull list-building/formatting logic out of composables into internal pure functions (`achievementHighlights()`) so it's unit-testable without a compose harness.
- Don't re-stub a whole interface in every fake — one abstract `StubX` base that `error`s on all methods, then each fake overrides only the calls its test exercises. Unexpected calls fail loudly.

## Misdirection
- No passthrough re-exports *or* private re-implementations of a shared util (a local `formatChips` that re-does `formatThousands`) — grep `libraries/` first, call the real one.
- Don't clone a private composable into a sibling file under a dodge-the-clash rename (`SheetInfoCard` == `InfoCard`) — share one internal impl in the package.
- Kill enum params whose branches all resolve to the same value (`IconTone.Gold` == `IconTone.Accent`) — a distinction the renderer ignores is a lie.
- User-facing strings go in `:libraries:resources`, even inside enums (`ShopSection("Card backs")` was a violation) — no inline English.

## Not slop — leave alone
- Emoji that are the affordance (avatar glyphs, reward icons, suit marks) are intentional here; don't strip them.
- The project's descriptive names (`didSeeInitialGrantInOnboarding`, `avatar…OrNull`) are its vocabulary, not verbose AI naming.
