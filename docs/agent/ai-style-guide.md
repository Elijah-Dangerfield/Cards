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

## Compose & previews
- Import `Preview` from `org.jetbrains.compose.ui.tooling.preview.Preview` — never write the fully-qualified `@org.jetbrains…Preview` inline.
- Import symbols; don't inline a fully-qualified reference (`PreviewContent`, `KLog`, `Clock`, achievement registries). One import reads far better than an FQN at the callsite.
- Every public screen-level / component composable has at least one `@Preview` covering meaningful states, not just happy path.

## Dead code
- Delete unused private helpers as you touch a file. A `default…()`/factory with no caller is dead, not "kept for later."
- Production code reachable only from its own tests is dead — flag it (its tests may be masking that nothing else uses it).
- Delete unused imports as you touch a file. A copy-pasted import can *look* used but isn't — a named argument `label = x` does not use an imported `label` symbol.

## Naming & clarity
- Don't shadow an outer `val` with an inner one of the same name — rename the inner (e.g. `previousHumans` → `priorHumans`) so each read is unambiguous.

## Not slop — leave alone
- Emoji that are the affordance (avatar glyphs, reward icons, suit marks) are intentional here; don't strip them.
- The project's descriptive names (`didSeeInitialGrantInOnboarding`, `avatar…OrNull`) are its vocabulary, not verbose AI naming.
