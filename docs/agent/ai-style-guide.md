# AI style guide

Short, scannable "do this / not that" tips distilled from real janitor cleanups, so future
agents stop repeating the same mistakes. Keep it a skim-in-under-a-minute checklist — one line
per tip, imperative, grouped. Tighten or merge before growing. Read alongside `AGENTS.md`.

## Logging & telemetry
- Never log per-recomposition, per-flow-emission, per-engine-tick, or per-bot-iteration — it floods Sentry/Loki/Tempo. Log once per hand / user action / state transition.
- Delete leftover debug scaffolding before shipping (tagged investigation logs like `[some-bug-tag]`, hash-code dumps, "does this fire?" probes). It's not telemetry.

## Comments
- Keep only genuine WHY (a gotcha, a regression it guards, "why this way not the obvious way"). Delete narration that restates the next line.
- Don't delete a substantive WHY just because it's long — over-trimming good context is worse than leaving it.
- Fix KDoc that describes code that no longer exists (renamed actions, removed flows, "used to render here" history) — a wrong doc misleads worse than none.

## Compose & previews
- Import `Preview` from `org.jetbrains.compose.ui.tooling.preview.Preview` — never write the fully-qualified `@org.jetbrains…Preview` inline.
- Import symbols; don't inline a fully-qualified reference (`PreviewContent`, `KLog`, `Clock`, achievement registries). One import reads far better than an FQN at the callsite.
- Every public screen-level / component composable has at least one `@Preview` covering meaningful states, not just happy path.

## Dead code
- Delete unused private helpers as you touch a file. A `default…()`/factory with no caller is dead, not "kept for later."
- Production code reachable only from its own tests is dead — flag it (its tests may be masking that nothing else uses it).
- Delete unused imports as you touch a file. A copy-pasted import can *look* used but isn't — a named argument `label = x` does not use an imported `label` symbol.
- A sealed/enum case mapped in the UI but never *emitted* is dead — check the producer, not just the consumer (a rendered `…ComingSoon` no VM sets). Drop the case, its mapping, and its string.

## Naming & clarity
- Don't shadow an outer `val` with an inner one of the same name — rename the inner (e.g. `previousHumans` → `priorHumans`) so each read is unambiguous.

## Misdirection
- No passthrough re-exports *or* private re-implementations of a shared util (a local `formatChips` that re-does `formatThousands`) — grep `libraries/` first, call the real one.
- Kill enum params whose branches all resolve to the same value (`IconTone.Gold` == `IconTone.Accent`) — a distinction the renderer ignores is a lie.
- User-facing strings go in `:libraries:resources`, even inside enums (`ShopSection("Card backs")` was a violation) — no inline English.

## Not slop — leave alone
- Emoji that are the affordance (avatar glyphs, reward icons, suit marks) are intentional here; don't strip them.
- The project's descriptive names (`didSeeInitialGrantInOnboarding`, `avatar…OrNull`) are its vocabulary, not verbose AI naming.
