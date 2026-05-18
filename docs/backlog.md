# Backlog

Ideas and follow-ups we want to remember but aren't doing right now. Append-only; move items into `decisions.md` once shipped or formally rejected.

---

## Bot bet-sizing tells

**Idea:** Have bots treat the human's bet size as a *signal* (in addition to the existing pot-odds math). Right now bots only react to bet size mathematically — a big bet costs more to call, so marginal hands fold. They don't interpret "this is a 3× pot overbet from a tight player, that means something."

**Sketch:**
- Compute the bot's expected bet size for its own perceived strength on this street.
- If the human's bet is materially larger than that, nudge the bot's estimate of opponent strength upward (calls less, folds marginal hands more).
- If the human's bet is materially smaller, nudge it downward (calls wider).
- Personality-weight the sensitivity: David and Gina pay attention; Mike basically ignores it; Steve and Jane react mildly.
- Add a small random `bluff-suspicion` term so the bot occasionally calls anyway — keeps it from feeling deterministic and lets the human bluff get through.

**Guardrails:**
- Keep the per-action equity shift small (think 5–15%, not 50%). It's a flavor, not an exploit.
- Don't apply to all bots equally — variance across personalities is what makes the table feel alive.
- No memory of the human's prior bluffs beyond what `OpponentTracker` already captures. We don't want bots to develop a "tell book" on the player.

**Tradeoff:** Done too aggressively, the bots start nailing every bluff and the table feels paranormal. Done too subtly, no one notices. The magnitude and the per-personality split is where the work is.

**Status:** Backlog. Revisit when the table feels "mathematically right but emotionally flat" — i.e., when the existing pot-odds bots feel too consistent across personalities.

---

## Multiplayer table — opponents-row overflow

**Idea:** With 6–10 players, the current `OpponentsRow` (BoxWithConstraints + Row + weight) packs avatars in until they're too small to be legible. For multiplayer, scroll instead of shrinking.

**Sketch:**
- Replace the Row with a `LazyRow` once `count > N` (probably 4, since at 5+ the current avatar scaling already drops below 56dp).
- Auto-scroll to the active actor when their turn flips, with a smooth animation. Otherwise the user has no idea where to look at a 10-handed table.
- Horizontal fading edges (gradient overlay on both sides) signal there's more off-screen.
- Optionally a "X folded · Y to act" peripheral tally above the row so the user keeps overview context even when most of the table is scrolled out.

**Guardrails:**
- Keep the existing pack-and-shrink behavior for `count <= 4` so casual bot tables still show everyone at once.
- Don't auto-scroll if the user has manually scrolled in the last few seconds — respect their context, snap back when their turn comes.

**Tradeoff:** At 6+ players you lose the "everyone at once" overview, which makes the table harder to read at a glance. The fading edges and the auto-scroll-to-actor pattern mitigate this, but it'll never be as legible as 4-handed.

**Status:** Backlog. Implement when multiplayer feature work begins — no value until there are actually >4 seats in production.

---

## Audio infrastructure (sound cues, BGM)

**Idea:** Add a small KMP audio playback layer so "Your turn feedback = Sound" (and future cues — winning a hand, achievement unlock, table ambience) can actually play a tone instead of being a no-op. Today the setting persists but only the Vibrate option is wired (via Compose `HapticFeedback`); Sound is recorded in `AppData` but the human just gets silence.

**Sketch:**
- New `:libraries:audio` KMP module with an `AudioPlayer` interface.
- Android `expect` → `actual` impl backed by `SoundPool` or `MediaPlayer` (short cues = SoundPool).
- iOS `actual` impl backed by `AVAudioPlayer`.
- Bundle a tiny library of WAV/MP3 cues in `compose-resources` — start with one "your turn" chime.
- Inject via DI; hand it to `PlayBotsScreen` (or a `TurnFeedbackPlayer` wrapper) so it can `play(Cue.YourTurn)` when `state.turnFeedback == Sound`.

**Tradeoff:** Audio adds binary size + a small init cost. Worth it for the "sound" preference to be honest, plus opens the door to other game cues later. Until then, "Sound" is effectively a no-op (Mute and Sound behave the same).

**Status:** Backlog. Vibrate works now via Compose haptics; ship Sound when it's worth the platform-actuals overhead.
