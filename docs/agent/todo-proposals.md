# 2026-05-29 strings-enforcement investigation

1 proposal — output of the worker-investigate-and-recommend `docs/todo.md` §C "Decide on a strings-enforcement mechanism + wire it up" item. The bullet removes from `docs/todo.md` in this cycle; ship of the recommendation waits on human triage.

## C. Wire a Gradle `verifyStrings` task, fail in CI

**Problem:** [`AGENTS.md` §strings (line 389)](../../AGENTS.md) requires every user-facing string to live in `:libraries:resources` and be read via `stringResource(Res.string.foo)` / `getString(...)`. The rule regresses periodically. Quick grep over `features/**` finds present-day raw literals — e.g. `features/shop/impl/.../PurchaseConfirmSheet.kt:189` (`text = "Charged via your ${platformStoreName()}."`), `features/upgrade/impl/.../AppGuardLayer.kt:237` (`text = "App content"`, likely preview-scoped but un-annotated), and the QA menu's intentional debug copy. Nothing today enforces the boundary — the rule lives in agent docs, not the build.

**Evidence:**
- Repo has **no Detekt** wired (`find . -maxdepth 3 -name 'detekt*.yml'` returns empty; `gradle/libs.versions.toml` has no `detekt-*` entries).
- CI gate in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) runs `./gradlew :apps:compose:assembleDebug` + `./gradlew testDebugUnitTest` + `./gradlew :apps:server:test`. Adding a new check task wired into `check` would run on every PR with zero new tooling.
- Pre-commit hook surface lives in [`.githooks/`](../../.githooks/) — already has `commit-msg` for conventional-commits. Adding a `pre-commit` is feasible but bypassable with `--no-verify`, which the agent prompts say to avoid but humans use freely.
- Convention plugins exist in `build-logic/` — a `cards.compose.multiplatform` plugin already opts modules into Compose; that's the right injection point if we add a build-level check.

**Options surveyed:**

| Option | Catches | Local feedback | CI gate | Effort to wire | Effort to maintain |
| --- | --- | --- | --- | --- | --- |
| Detekt custom rule | AST-precise; can match `Text("…")` excluding stringResource | `./gradlew detekt` | yes | high (new plugin + ruleset jar) | medium (rule code drift with Detekt versions) |
| Pre-commit grep hook | regex-only; misses multi-line; bypassable with `--no-verify` | instant | no | low | low |
| Pre-push CI check (grep job) | regex-only | none locally | yes | low | low |
| Gradle `verifyStrings` task wired into `check` | regex-only | `./gradlew check` | yes (CI runs `check`) | low | low |

**Recommended pick — Gradle `verifyStrings` task wired into `check`.** Same regex precision as the simpler options but with **two channels at once**: local devs hit it when they run `./gradlew check` before pushing, and CI hits it on every PR by virtue of `check` being the umbrella task. No new tooling to maintain (Detekt brings a plugin + ruleset jar + version drift; Gradle task is ~30 lines of Kotlin). No `--no-verify` escape (Gradle isn't a git hook). Single configuration point so the allowlist / file-pattern logic lives in one file.

**Rejected alternatives:**
- **Detekt custom rule** — overkill for one rule. The plugin add brings every other Detekt finding the repo hasn't opted into (style, complexity, naming) into the gate, or requires a curated config to suppress them. Cost > benefit for one check today; revisit if the lint surface grows past 2-3 rules.
- **Pre-commit grep hook** — too easy to bypass with `--no-verify`, and the agent-worker prompts explicitly forbid `--no-verify` so workers never see the check locally. The check has to gate at the build, not at the hook.
- **Pre-push CI check alone** — feedback comes after push, so devs ship `Text("…")` then have to push a fix-up. Local-first signal is cheap and worth keeping.

**Suggested item shape:**

`[P2]` **Wire `verifyStrings` Gradle task.** A `verifyStrings` task in the root build (or `build-logic/cards-compose-multiplatform`) scans every `*.kt` file under `commonMain`, fails the build if it finds `Text\s*\(` followed by a literal `"…"` argument **outside `:libraries:resources` and outside `*Preview*.kt`**. Wire it into `check` so `./gradlew check` runs it locally, and rely on the existing CI step `./gradlew testDebugUnitTest` to escalate it (or add `./gradlew check` explicitly if `testDebugUnitTest` doesn't trigger the wider `check` aggregate — confirm in a 5-min spike before wiring). **Acceptance:**
- Adding a `Text("Hello")` literal to any feature `:impl` module under `commonMain` fails `./gradlew check`.
- The same string read via `stringResource(Res.string.foo)` passes.
- An explicit allowlist comment (e.g. `// strings-enforce: skip` on the same line) is honored — escape hatch for glyph-only typography per AGENTS.md.
- The QA menu (debug-only) is excluded by file-path rule rather than per-line allowlist.

**Files / hints:**
- New task lives in `build-logic/src/main/kotlin/CardsVerifyStrings.kt` (or inlined into the existing compose convention plugin).
- Regex baseline: `\bText\s*\(\s*("[^"\\]+"|text\s*=\s*"[^"\\]+")` against `*.kt` files under `**/src/commonMain/**` excluding `**/libraries/resources/**` and `**/Preview*.kt`.
- Wire into `check` via `tasks.named("check") { dependsOn(verifyStrings) }`.

**Out of scope:**
- Migrating the existing real violations (`PurchaseConfirmSheet.kt:189`, `AppGuardLayer.kt:237`, plus whatever else the first run surfaces). The task lands behind a baseline-allowlist file (`docs/strings-baseline.txt`) so the gate goes green on day one; cleanup tracked as a separate `[P2]` item.
- Extending the rule to catch other DS rules (raw `Color.White.copy(...)`, `dp` literals where Dimension exists). Same task can grow, but expanding the surface now is feature creep.

**Reviewer notes (worker):**
- The regex approach has false-positive risk on multi-line `Text(text = "${expr}")`. The recommendation lives at "fails the build" granularity, not "perfectly classifies every literal" — a wrong regex flag gets a `// strings-enforce: skip` annotation per-line, same as Detekt baselines. Acceptable tradeoff given the alternative (Detekt) is meaningfully more infra.
- I considered making the check a *warning* not a *failure* on first land (lint-style), to ease the migration. Rejected — every regression today is "it lives in agent docs but nothing enforces it"; flipping it to a warning recreates the same enforcement gap.
- One thing I didn't surface in the table: **IDE integration.** Detekt has an IntelliJ plugin that surfaces findings inline. The Gradle-task pick has no IDE surface — devs only see it on `./gradlew check`. Tolerable but worth knowing. If IDE-inline lint becomes load-bearing, the migration path is "wrap the regex in a Detekt custom rule" — same regex, new harness — without re-doing the investigation.
