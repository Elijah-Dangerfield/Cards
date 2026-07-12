# In-flight

## fix(gameplay): scrub undealt deck from broadcast state (MP-31)

**Problem:** `GameState.deckRemaining` — the exact future flop/turn/river, with no burn cards — was serialized to every seated client on each state update; `scrubbedFor()` only emptied other seats' hole cards, so a modified client could read the whole runout.
**Approach:** `scrubbedFor()` now always empties `deckRemaining` alongside the hole-card scrub. Verified only the server-side `GameEngine` reads `deckRemaining` (from its own authoritative state); nothing on the socket/client path consumes it, and solo `LocalBotsSession` builds its own deck. Added two regression tests asserting the scrubbed deck is empty preflop and at showdown.
**Reviewer notes:** None. The engine keeps operating on the un-scrubbed authoritative state, so gameplay is unaffected.

## fix(bots): tame curiosity/slack calling so bots stop hero-calling trash (GAME-31)

**Problem:** `BotDecision.choose` applied a difficulty-independent curiosity call (~0.10-0.20) with no bet-size term, plus a flat +0.10 pot-odds slack, so bots hero-called (even called all-ins) with any hand ~10-20% of the time — the "the bot cheated / saw my cards" complaint, and a chip leak.
**Approach:** both curiosity and the pot-odds slack now decay to zero via a shared `priceDamp = 1 - potOdds/0.33` factor (zero at a pot-sized bet or bigger), and the curiosity ceiling is gated by difficulty (Casual 0.16 / Standard 0.08 / Challenging 0.03), still scaled down by tightness. Considered a hard bet-size cutoff (call/no-call switch) but a smooth damp keeps light calls at small sizings without a cliff. Two tests: trash folds a shove across all 200 seeds (was leaking before), and light calls still occur at tiny sizings.
**Reviewer notes:** Constants (0.33 cutoff, ceilings) are tuned by judgement, not sim-swept; the existing `botsBeatNaiveAllInShoverOverManyHands` regression still passes, which is the strongest signal they didn't over-tighten.
