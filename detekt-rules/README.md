# :detekt-rules

Custom [detekt](https://detekt.dev) rules that mechanically enforce Cards conventions
(ENG-2). The root `detekt` task runs them over every module's Kotlin source, behind
`config/detekt/baseline.xml`, and `.githooks/pre-push` runs them before each push.

## Rules
- **VerifyStrings** — fails on inline user-facing string literals passed to a DS text
  composable (`Text` and its wrappers), so copy comes from `stringResource(...)` rather
  than being hardcoded. Allowlists glyph-only literals, `@Preview` bodies, and non-literal
  arguments (variables, `stringResource(...)`, server-supplied strings).

## Adding a rule
1. Add a `Rule` subclass in `src/main/kotlin/.../detekt/` (extend `dev.detekt.api.Rule`).
2. Add its `::Constructor` reference to `CardsRuleSetProvider.instance()`.
3. Enable it under `cards:` in `config/detekt/detekt.yml`.
4. If it flags existing code, regenerate the baseline: `./gradlew detektBaseline`.

## Gotcha: restart the daemon after editing a rule
The Gradle daemon caches detekt's rule classloader, so an edited rule keeps linting against
its *previous* compiled version until the daemon restarts. After changing a rule run
`./gradlew --stop` (or pass `--no-daemon`) before `detekt` / `detektBaseline`, or you'll
verify against stale behaviour.

## No unit tests (yet)
`dev.detekt:detekt-test` is intentionally omitted: alpha.5's `detekt-api` test-fixtures jar
isn't published to Maven Central, so the dependency can't resolve. Verify a rule by running
the real `detekt` task over a sample file. Add `detekt-test` back once the alpha publishes
its fixtures.
