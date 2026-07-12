# In-flight

## fix(gameplay): scrub undealt deck from broadcast state (MP-31)

**Problem:** `GameState.deckRemaining` — the exact future flop/turn/river, with no burn cards — was serialized to every seated client on each state update; `scrubbedFor()` only emptied other seats' hole cards, so a modified client could read the whole runout.
**Approach:** `scrubbedFor()` now always empties `deckRemaining` alongside the hole-card scrub. Verified only the server-side `GameEngine` reads `deckRemaining` (from its own authoritative state); nothing on the socket/client path consumes it, and solo `LocalBotsSession` builds its own deck. Added two regression tests asserting the scrubbed deck is empty preflop and at showdown.
**Reviewer notes:** None. The engine keeps operating on the un-scrubbed authoritative state, so gameplay is unaffected.

## fix(bots): tame curiosity/slack calling so bots stop hero-calling trash (GAME-31)

**Problem:** `BotDecision.choose` applied a difficulty-independent curiosity call (~0.10-0.20) with no bet-size term, plus a flat +0.10 pot-odds slack, so bots hero-called (even called all-ins) with any hand ~10-20% of the time — the "the bot cheated / saw my cards" complaint, and a chip leak.
**Approach:** both curiosity and the pot-odds slack now decay to zero via a shared `priceDamp = 1 - potOdds/0.33` factor (zero at a pot-sized bet or bigger), and the curiosity ceiling is gated by difficulty (Casual 0.16 / Standard 0.08 / Challenging 0.03), still scaled down by tightness. Considered a hard bet-size cutoff (call/no-call switch) but a smooth damp keeps light calls at small sizings without a cliff. Two tests: trash folds a shove across all 200 seeds (was leaking before), and light calls still occur at tiny sizings.
**Reviewer notes:** Constants (0.33 cutoff, ceilings) are tuned by judgement, not sim-swept; the existing `botsBeatNaiveAllInShoverOverManyHands` regression still passes, which is the strongest signal they didn't over-tighten.

## feat(server): scale matchmaking bot difficulty by stake tier (MP-33)

**Problem:** public matchmaking filled bot seats with `BotDifficulty.Standard` regardless of buy-in, so a Premium table had the same soft bots as a Casual one — a named competitor complaint. Solo already couples difficulty to stake; MP didn't.
**Approach:** added `StakeTier.fromBuyIn(buyIn)` (gameplay) to bucket any buy-in (named or custom) into its tier, and `StakeTier.toBotDifficulty()` (bots, the inverse of solo's mapping): Practice/Casual to Casual, Standard to Standard, High/Premium to Challenging. The play-bots fallback now derives difficulty from `room.buyIn`, and the `ServerBotDriver` restart-fallback derives it from the table's starting stack so a revived High/Premium bot stays sharp. Chose a floor-to-tier bucket over exact-buy-in matching so custom-buy-in tables still scale.
**Reviewer notes:** Private-room host bot difficulty is unchanged — that path goes through the host's explicit `addBot`/`fillBotsUpTo` call with a chosen difficulty, not this public fallback. The tier-to-difficulty split (only 3 difficulties across 5 tiers) is a judgement call; Premium and High both landing on Challenging is the ceiling the engine offers today.

## feat(server): adapt multiplayer bots to opponents via a per-session read (MP-32)

**Problem:** the adaptive `OpponentTracker` layer (shove-monster / passive-caller reads → call lighter vs a jammer) was fed only in solo. `ServerBotDriver` called `BotDecision.choose` with no tracker, so MP bots faced everyone with a fresh empty read and never adapted — the samey-bot complaint, for the bots most players actually meet.
**Approach:** the driver now owns one `OpponentTracker`, fed from `session.events` on its own child job, and passes it into every `choose`. Also added an `isHabitualAggressor` read (serial big-bettor who isn't a literal jammer) wired into the opponent adjustment at +0.06 strength bias (less than a shove-monster's +0.10), using the `aggCount`/`aggressionFrequency` signals that were tracked but unused. Made `OpponentTracker` safe for the concurrent write (event collector) vs read (decision thread) by publishing its profile map as an immutable value behind `@Volatile` and replacing it whole per update.
**Reviewer notes:** The read lags decisions by up to one action (event collection is async) — fine, opponent modeling is inherently historical, noted in code. The driver test proves the events→tracker wiring (the injected tracker flags the human's shoves); the read's effect on decisions is covered in `:libraries:bots` (existing `botsBeatNaiveAllInShoverOverManyHands` still passes, plus new habitual-aggressor tests). Bias magnitudes are judgement-tuned.
