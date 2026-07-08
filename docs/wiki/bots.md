# Bots — fairness as a perception problem

We treat "the AI feels rigged" as a first-class V1 design problem, distinct from "the AI is actually rigged." Heuristic bots that semi-bluff or chase draws will inevitably win some runner-runner pots; players remember those hands and conclude cheating. From a 16-review competitor survey, "AI cheats" appeared in roughly two-thirds of the negative reviews — even mathematically-fair bots inherit this complaint unless we proactively defuse it.

## What's built

1. **Three difficulty tiers** — `Casual` / `Standard` / `Challenging`. Each tier changes the personality mix (`BotPersonality.forDifficulty`) *and* the decision parameters: fold/raise thresholds shift per tier, and bluff + semi-bluff frequency are both zero at Casual (`BotDecision.computeBluffChance` / `computeSemiBluffChance`).

2. **Opponent modeling and tactical play are opt-in by difficulty.** Casual bots get no opponent-tracker adjustment and no positional/situational tactics (steals, c-bets, 3-bet tightening) — both branches return neutral for `Casual` in `BotDecision`. A new player isn't being read by an adaptive opponent in their first hour.

3. **Casual bots don't bluff or chase draws aggressively.** No bluffs, no semi-bluff raises at the Casual tier. They do still make loose *calls*: every tier (Casual included) calls marginal spots via a pot-odds slack (`potOdds <= strength + 0.10`) plus a random "curiosity call" chance, deliberate humanlike noise so bots don't fold robotically. So "Casual only makes pot-odds-positive calls" is **not** a property the code guarantees.

4. **Every decision produces a `BotThought`** (hand strength, pot odds, draw profile, opponent note, rationale string). Today it's used only to pace think-time delays — a close decision "thinks" longer (`ServerBotDriver.thinkDelay`, `BotTiming`). It is not persisted, not sent to clients, and not visible anywhere in the UI.

## Not implemented

Earlier drafts of this page listed these as shipped V1 countermeasures. None exist in the code yet; they're candidate follow-ups, and `BotThought` is the natural seed for the first two:

- **Showdown transparency** — showing what the bot held and its equity at each decision point after a hand.
- **Bot-thought hand history** — replaying a past hand with each bot's per-street rationale.
- **Provably-fair shuffle** — publishing a SHA-256 commit of the shuffled deck at hand start and revealing the seed at showdown.

## How to apply

When adding a bot behaviour, ask: *does this fail safe at the Casual tier?* If a new aggressive line shows up at Casual, walk it back to Standard / Challenging — the Casual tier's job is to feel fair more than to play well.

If a transparency surface (showdown equity, bot-thought history) ever ships, preserving it matters as much as the underlying math being correct. The fairness story falls apart if a curious user can't see what the bot held.
