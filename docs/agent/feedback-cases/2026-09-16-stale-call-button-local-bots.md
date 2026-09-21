# 2026-09-16 — stale Call button silently no-ops in a local-bots hand

**Signal:** Loki client stream, `{service_name="cards-client", deployment_environment="prod"}`,
2026-09-16 ~14:52Z UTC. One WARN line, tag `PlayPokerViewModel`, `exception_type
IllegalArgumentException`, message `Nothing to call`. No Sentry issue exists for this — it's
WARN-level, and `SentryLogTree` only forwards ERROR+ throwables (per the standing ENG-29
finding), so this channel is Loki-only.

**Scope:** ONE event, one session (`7ace97a2…`/`2dd4f5ea…` — the client rotates `session_id`
mid-run; both belong to install `6793e029-da93-4dc3-8803-c5fc9b250210`, a genuine retail
Android install, `store` release channel, build 0.1.0+1135). `LocalBotsSession` (solo/bots
play, not multiplayer — no server involvement).

**What the log shows:** immediately before the exception, the same session logged three "Bot
decision for seat N is stale … Skipping apply" lines (`LocalBotsSession.kt`, the documented
defensive re-check for a bot decision computed against a game state that has since moved on).
The very next log line is the `IllegalArgumentException("Nothing to call")` tagged
`PlayPokerViewModel`.

**Where it's thrown:** `GameEngine.kt:331` — `resolveAction` requires `toCall > 0` for a
`PlayerIntent.Call`. It's caught at `PlayPokerViewModel.kt:982-999` inside the
`PlayPokerAction.Submit` handler (`Catching { session.submit(action.intent) }.onFailure { e ->
logger.w(e) { "submit failed for ${action.intent}" } ... }`), which explicitly branches on
`IntentTimeoutException` and `IntentRejectedException` for user feedback — `IllegalArgumentException`
falls through the `else -> Unit`, so **nothing is shown to the player**. The haptic
(`PlayHaptic(ActionTaken)`) and chip-click sound already fired optimistically before the async
submit resolves, so the tap *feels* acknowledged even though it silently failed.

**What's proven vs inferred:** proven — the exception is real, caught, non-fatal, and produces
zero user feedback. Inferred, not confirmed — the theory that this is the same staleness class
as the adjacent bot-decision-skip warnings (i.e., the human tapped Call while the client's copy
of `gameState` had already advanced past a bet the UI hadn't re-rendered yet, so `toCall` read 0
by the time `submitHumanIntent` ran). `LocalBotsSession.submitHumanIntent` re-validates legality
against its own current `gameState` synchronously before applying
(`isHumanIntentLegal`/`GameEngine.applyIntent` both read the same object in the same call), so a
same-thread TOCTOU race within that function looks unlikely from the code alone; something
upstream of it — a Compose recomposition lag on the Call button's enabled state, or a coroutine
interleaving between the UI's cached `toCall` and the session's live state — is the more likely
gap, but this single log line doesn't pin down which. Only one occurrence exists to reason from.

**Disposition:** todo GAME-35 `[P2]` (2026-09-16) — single occurrence, non-crashing, but a real
correctness/UX defect (a tap the player believes succeeded, silently no-ops) worth a proper
repro rather than a guess. No Sentry issue to resolve (never reached Sentry).
