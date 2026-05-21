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

## Daily / return-visit reward (non-streak)

**Idea:** A small, optional reward for returning to the app — *without* the daily-streak loss-aversion framing we explicitly rejected ([product-spec.md Appendix C.1](./product/product-spec.md#c1-daily-login-streak-rejected-2026-05-16)). The motivation isn't "punish you for skipping a day"; it's "be warm when you come back."

**Sketch directions to consider (pick one — they're not additive):**
- **Variable surprise.** Every Nth return visit (probabilistically) drops a small chip bonus + a custom message. No counter shown — surprise, not obligation.
- **Weekly play-streak** (already in product-spec.md Appendix B item 17). Consecutive *weeks* with ≥1 hand played. Lower-pressure than daily.
- **"Welcome back" only after gaps.** Reward triggers if it's been ≥7 days since last play — re-engagement, not retention pressure.
- **First-hand-of-the-day chip-coin.** Tiny bonus on your first hand played each calendar day. No counter, no streak number, just a one-time "+50" badge that hand. Easy to add, easy to remove.

**Guardrails:**
- **No counter, no streak number, no "you'll lose your X if you skip."** That framing is the exact thing we said no to.
- **No notification spam.** Reward is discovered when you open the app, never pushed to your lock screen.
- **Reward small enough to not affect economy balance.** This is warmth, not a chip faucet.

**Tradeoff:** Even the gentlest version drifts toward daily-obligation framing if scoped wrong. The decision math is whether retention numbers justify the risk to the brand. Revisit after V1 ships and we have D2/D7 data — if retention is healthy without it, leave it alone.

**Status:** Backlog. Considered + deferred 2026-05-20.

---

## Felt-aware button color adaptation

**Idea:** When the user equips a colored felt, the play-screen action buttons can clash. Either pin the buttons to a felt-independent surface, or token the buttons against a `surfaceOnFelt` color that the felt itself defines.

**Sketch:**
- Each felt declares its own `surfaceOnFelt` + `onSurfaceOnFelt` pair in its catalog entry.
- The play screen's action button strip reads from `LocalCurrentFelt.surfaceOnFelt` (composition local set at the play screen root) instead of `AppTheme.colors.surfacePrimary`.
- Fallback to surface tokens when no felt is equipped or the felt doesn't declare an override.

**Tradeoff:** Right now the play surface is dark enough that the clash is mild. The fix isn't expensive but it's tokens + catalog metadata, not a one-line change. Worth doing when we have time to extend the catalog schema, or when we ship a brighter felt that makes the clash actually painful.

**Status:** Backlog. Deferred 2026-05-20 — not a V1 blocker; current felt palette is forgiving.

---

## Emojis-cost-chips as a chip sink

**Idea (raised 2026-05-20):** Make each table-side emoji blast cost a small chip amount, so emojis become a chip sink that drives buying.

**Dissent (recorded 2026-05-20):** I'd push back. Emojis are the social-signal feature that makes the table feel alive. Adding cost suppresses usage, which suppresses the social experience, which suppresses the loss-aversion-on-busts loop that actually drives chip purchases. The chip-sink instinct is right — the lever is wrong.

**Better chip sinks to consider first:**
- **MP buy-in / ante** (already on todo.md as a separate item). This is the natural chip sink in a poker game.
- **Tip the dealer** at hand end (already in [product-spec.md §4.1.5](./product/product-spec.md#41-currency--chips)).
- **Profile rename / title change cost.**
- **Custom avatar slots, name color, name glow, profile decoration** — already in shop catalog (§4.3).

**Status:** Backlog. Revisit only if the other sinks (especially MP buy-in) prove insufficient to keep chips a flowing resource. Default position: do not charge for emojis.

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

---

## RankDetail hero gradient — raw brand colors

**Idea (raised 2026-05-20):** `RankDetailSheet.RankHero` (~lines 88–92) uses raw `Color(0xFF8E7CC3)` + `Color(0xFFE07AB1)` for the hero gradient — an AGENTS.md DS-first violation (no `Color(0xFF…)` outside `PokerPalette`). Two directions:

- **Promote both to `PokerPalette`** as the canonical "rank hero" pair, named for what they are visually (e.g. `rankHeroStart`, `rankHeroEnd`). Cheap, keeps the gradient working as-is.
- **Replace with theme tokens** — likely `accentPrimary` and a sibling. Loses the bespoke purple→pink hue but pulls the surface into the semantic palette.

Needs a designer call on whether the bespoke gradient is load-bearing for the Rank surface's identity. Touched-but-not-fixed during the 2026-05-20 white-alpha pill cleanup.

**Status:** Backlog. Needs a design decision before swap.

---

## Route default `popExit` = reversal of `enter`

**Idea (raised 2026-05-20):** After the cover-and-uncover NavHost rewire, every existing `Route` subclass still has to declare `popExit` explicitly to mirror `enter`. The default in `Route(...)` is hardcoded `AnimationType.SlideOutToRight`, which is fine for horizontal-slide routes but wrong by default for `SlideUp` / `FadeIn` / etc. — a new route that forgets to declare `popExit` will pop horizontally regardless of how it entered.

Worth deriving the default — `popExit: AnimationType = enter.reversal()` (the existing `opposite()` is a *mirror* not a *reversal*, so it needs renaming or a sibling). Then every per-route `popExit` declaration that matches the derivation can be dropped.

Blocker on doing it now: `opposite()` currently maps `SlideInFromRight → SlideOutToLeft` (mirror), but the reversal we want for back-out is `SlideInFromRight → SlideOutToRight` (back the way it came). Renaming / fixing the mapping is a semantic call worth a deliberate pass rather than tucking into the wiring change.

**Status:** Backlog. Captured 2026-05-20 alongside the NavHost cover-and-uncover wiring.

---

## `FeatureCard` glyph block — white-alpha on accent gradient

**Idea (raised 2026-05-21):** [`FeatureCard.kt:60`](../libraries/ui/src/commonMain/kotlin/com/cards/libraries/ui/components/FeatureCard.kt#L60) renders the leading glyph block with `Color.White.copy(alpha = 0.15f)` — the exact pattern AGENTS.md DS rule §1 calls out. It's a hand-tuned tile on top of a gradient (the card's `accent.copy(alpha = 0.95f) → accent.copy(alpha = 0.7f)` horizontal gradient), so a flat `surface*` token would clash with the gradient.

Two directions:
- **New DS token for "overlay on accent surface."** Something like `AppTheme.colors.surfaceOverlayOnAccent` that resolves to a translucent neutral wash. Lets every accent-gradient card get the same glyph treatment without duplicating the magic alpha.
- **Pin to `surfacePrimary.copy(alpha = X)`.** Cheaper, but ties the glyph block to whatever the surfacePrimary token resolves to under the gradient — needs an eyeball check across all four `FeatureCardAccents` (Green/Blue/Magenta/Gold).

Needs a designer call on which approach the DS should codify.

**Status:** Backlog. Surfaced during the 2026-05-21 Radii-token sweep.

---

## `Route.exit` field — dead after cover-and-uncover wiring

**Idea (raised 2026-05-21):** After the NavHost `exitTransition` was collapsed to `ExitTransition.None` (cover-and-uncover semantics), `Route.exit` is no longer read by the host. ~7 Route subclasses still declare it. Separable cleanup: drop `exit` from `Route(...)` and every subclass, then drop the dead `getExitTransition()` accessor and the `toExitTransition()` callers that route through it.

Blocker on doing it now: should land alongside the `popExit = enter.reversal()` derivation (already in this backlog under "Route default `popExit` = reversal of `enter`") so the per-route animation surface gets rationalised in one pass rather than two.

**Status:** Backlog. Captured 2026-05-21 alongside the NavHost cover-and-uncover wiring.

---

## Sweep remaining game-table corner literals → `Radii` tokens

**Idea (raised 2026-05-20):** `Radii.R700` (16.dp) was added during the RankDetail cleanup. `R800` (20.dp), `R900` (24.dp), and `R1000` (28.dp) were added 2026-05-21 alongside the non-game-table follow-up (`FeatureCard`, `AchievementMedallion`, `LobbyScreen`). The non-game-table 16.dp callsites were migrated 2026-05-21 (`MyItemsScreen`, `StatsScreen` ×3, `FeatureCard`, `QaMenuScreen` ×5 including the 10.dp `R400` ones). Remaining literals all live on game-table surfaces, which were tuned by hand for the play screen — worth a deliberate visual sweep rather than blind replace:

- `BoardArea.kt:94/98` — 16.dp (→ `R700`)
- `HandResultDialogs.kt:272` — 16.dp (→ `R700`)
- `PlayerArea.kt:102/103` — 20.dp (→ `R800`)
- `PlayerArea.kt:240/245` — 16.dp (→ `R700`)
- `HandRankingsCheatSheet.kt:266` — 28.dp (→ `R1000`)
- `HandRankingsCheatSheet.kt:382/422` — 20.dp (→ `R800`)
- `RaiseSheet.kt:206` — 28.dp (→ `R1000`)
- `TableActionBar.kt:138/167` — 28.dp (→ `R1000`)

**Status:** Backlog. Non-blocking DS drift; pull when next opening the play-table surfaces.

