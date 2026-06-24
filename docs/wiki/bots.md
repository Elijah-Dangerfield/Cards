# Bots — fairness as a perception problem

We treat "the AI feels rigged" as a first-class V1 design problem, distinct from "the AI is actually rigged." Heuristic bots that semi-bluff or chase draws will inevitably win some runner-runner pots; players remember those hands and conclude cheating. From a 16-review competitor survey, "AI cheats" appeared in roughly two-thirds of the negative reviews — even mathematically-fair bots inherit this complaint unless we proactively defuse it.

## V1 countermeasures

1. **Showdown transparency for bot games.** At end of hand, show what the bot held and its equity at each decision point. Turns a black-box outcome into something verifiable.

2. **Bot-thought hand history.** Replay any past hand in the session and see each bot's decision rationale per street.

3. **Provably-fair shuffle (multiplayer).** Server publishes a `SHA-256` commit of the shuffled deck at hand start and reveals the seed at showdown so anyone can verify.

4. **Three difficulty tiers** — `Casual` / `Standard` / `Challenging`. Each tier changes the personality mix *and* the underlying parameters: preflop aggression, semi-bluff frequency, draw-chasing conservatism.

5. **Casual-tier bots never speculatively chase draws.** Only pot-odds-positive calls. Directly reduces the "they hit the perfect river" feeling for newcomers.

6. **Opponent modeling is opt-in by difficulty.** Casual bots don't adapt to opponents; Standard and Challenging do. A new player isn't being read by an adaptive opponent in their first hour.

## How to apply

When adding a bot behaviour, ask: *does this fail safe at the Casual tier?* If a new aggressive line shows up at Casual, walk it back to Standard / Challenging — the Casual tier's job is to feel fair more than to play well.

When the transparency surfaces drift (bot-thought hand history, equity-at-decision-point), preserving them matters as much as the underlying math being correct. The fairness story falls apart if a curious user can't see what the bot held.
