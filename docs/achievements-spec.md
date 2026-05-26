# Achievements spec — V1 audit + V1.x proposals

**Last reviewed:** 2026-05-26 · **Status:** audit complete; re-anchor changes (Summary §1 + §2) landed; MP-keyed sibling ids (§3) pending Phase 4.2.

Audit of every achievement currently in [`AchievementRegistry.kt`](../libraries/cards/src/commonMain/kotlin/com/cards/libraries/cards/AchievementRegistry.kt), with proposed mode + threshold per row. The goal: every achievement reads the same way at every stake tier in every mode, or — where that's structurally impossible — it gets a mode-specific id with deliberate thresholds.

Companion to [`todo.md` → Achievements — bot-vs-human split](./todo.md#achievements--bot-vs-human-split). The todo bullet's "Approach" instruction was to write this spec before changing code; the registry changes land in a follow-up commit (or a second pass) once this doc reads true end-to-end.

## Why the audit

The V1 registry was authored when bots were the only opponents. Most rows default to `mode = EITHER` because it was the cheap call: the criterion (counter ticks, hand category, pot size) is mode-agnostic at the engine level, so allowing both modes to satisfy it costs nothing.

What changed: the [`StakeTier`](../libraries/gameplay/src/commonMain/kotlin/com/cards/libraries/gameplay/StakeTier.kt) work pinned per-tier blinds + buy-ins for both bot tables and the upcoming public-room matchmaking. The bot tiers (`Casual → Casual`, `Standard → Standard`, `Challenging → High`) push absolute chip thresholds far out of range for "real accomplishment" reads. Example: `POT_5000` ("be at a table for a 5,000-chip pot") is **trivial** on the Challenging tier where blinds are 100/200 and the buy-in is 20,000 — most contested hands cross 5K on the flop. The same threshold in MP Casual (blinds 5/10, buy-in 1,000) is a genuine all-in pot.

Three failure modes follow:
1. **Tier-blind thresholds** read absurdly easy or absurdly hard depending on where you're playing. (POT_*, comeback variants.)
2. **Bot-specific mechanics dressed as mode-agnostic** — Challenging-difficulty wins, bot-personality wins. Those rightly carry `mode = BOTS` already; this audit doesn't change them.
3. **MP-only-meaningful achievements missing entirely.** Busting a human ≠ busting a bot; the registry comment on `FIRST_BUST_DEALT` already flags this, and the MP variants don't exist yet.

This spec resolves failure modes (1) and (3); (2) is already correct.

## Conventions

- **Current mode** — what's in the registry today.
- **Proposed mode** — what should ship at V1 or get a split-id in V1.x.
- **Split id?** — Yes if the achievement needs a sibling `*_MP` id with different thresholds (or different criteria entirely). No if one row reads correctly in both modes.
- **Threshold rationale** — why the target value is correct (or known-broken) at each tier.
- **Ship target** — V1 (no change needed before launch), V1.x (when MP achievements land), or V2 (deferred).

## Rows

### Volume — `HandsPlayed(N)`

| Id | Current mode | Proposed mode | Split id? | Threshold rationale | Ship target |
|---|---|---|---|---|---|
| `FIRST_HAND` | EITHER | EITHER | No | "Did you finish a hand" reads identically in any mode. | V1 — no change |
| `HANDS_10` | EITHER | EITHER | No | Volume = volume. Splitting would imply MP hands "count more" — wrong message for a milestone that exists to acknowledge a new user has shown up. | V1 — no change |
| `HANDS_100` | EITHER | EITHER | No | Same as above. | V1 — no change |
| `HANDS_500` | EITHER | EITHER | No | Same as above. | V1 — no change |
| `HANDS_1000` | EITHER | EITHER | No | Same as above. The EPIC chip reward is fine — players who get there have earned it in some shape. | V1 — no change |

### Endurance — `Custom(NO_BUST_STREAK)`

| Id | Current mode | Proposed mode | Split id? | Threshold rationale | Ship target |
|---|---|---|---|---|---|
| `NO_BUST_50` | EITHER | EITHER | No | The streak is about bankroll discipline (don't shove garbage), which is mode-agnostic. MP is *harder* — humans punish loose play more — but the criterion semantics are the same. | V1 — no change |
| `NO_BUST_100` | EITHER | EITHER | No | Same as above. | V1 — no change |

### Hand-strength milestones — `ShowAtLeast(category)`

All eight rows: `SHOW_PAIR`, `SHOW_TWO_PAIR`, `SHOW_THREE_OF_KIND`, `SHOW_STRAIGHT`, `SHOW_FLUSH`, `SHOW_FULL_HOUSE`, `SHOW_FOUR_OF_KIND`, `SHOW_STRAIGHT_FLUSH`, `SHOW_ROYAL_FLUSH`.

| Group | Current mode | Proposed mode | Split id? | Threshold rationale | Ship target |
|---|---|---|---|---|---|
| All hand-strength rows | EITHER | EITHER | No | A flush is a flush; the achievement is "the rare cards lined up for you and you reached showdown to display them." Mode of the opponent is irrelevant to the criterion. | V1 — no change |

### Bot personality mastery — `Custom(winsVsBotKey(name))`

| Id | Current mode | Proposed mode | Split id? | Threshold rationale | Ship target |
|---|---|---|---|---|---|
| `BEAT_JANE_10`, `BEAT_DAVID_10`, `BEAT_GINA_10`, `BEAT_STEVE_10`, `BEAT_MIKE_10` | BOTS | BOTS | No | Personalities are bot-only by definition. Counter increments only fire when the opponent is the named bot. | V1 — no change |

### Difficulty milestones — `Custom(CHALLENGING_WINS)`

| Id | Current mode | Proposed mode | Split id? | Threshold rationale | Ship target |
|---|---|---|---|---|---|
| `CHALLENGING_FIRST_WIN` | BOTS | BOTS | No | Difficulty tiers only exist for bots. Already correct. | V1 — no change |
| `CHALLENGING_10_WINS` | BOTS | BOTS | No | Same. | V1 — no change |

### Comeback — `Custom(COMEBACK_5BB)` / `Custom(DONT_CALL_IT_COMEBACK_COUNTER)`

| Id | Current mode | Proposed mode | Split id? | Threshold rationale | Ship target |
|---|---|---|---|---|---|
| `COMEBACK_FROM_5BB` | EITHER | EITHER | No | The criterion is BB-relative (started hand with ≤5 BB, ended with ≥2× starting stack) — see [`AchievementRepositoryImpl.kt`](../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/AchievementRepositoryImpl.kt) `updateComebackCounter`. BB-relative makes it tier-agnostic by design; it should read the same on a Casual table as a Challenging one. | V1 — no change |
| `DONT_CALL_IT_COMEBACK` | EITHER | EITHER | No | ~~Broken at high tiers~~ — re-anchored to BB multiples. Arms when ending stack ≤ 10 BB (and > 0, so bust doesn't arm); fires when armed && ending stack ≥ 100 BB. Same id, criterion rewritten in [`AchievementRepositoryImpl.updateDontCallItComebackCounter`](../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/AchievementRepositoryImpl.kt). | **Landed** |

### Pot-size milestones — `Custom(MAX_POT_SEEN)`

This is the worst offender — every row uses absolute chip values that don't translate across stake tiers.

| Id | Current mode | Proposed mode | Split id? | Threshold rationale | Ship target |
|---|---|---|---|---|---|
| `POT_500` | EITHER | EITHER | No | 500-chip pot reads as "a normal contested hand" on most tiers. Threshold is generous enough that it's not absurd on the lowest tier (Practice buy-in 200 — would require a multiway pot, but plausible) and not embarrassing on higher tiers. Tolerable as-is. | V1 — no change |
| `POT_1000` | EITHER | EITHER | No | 1,000 reads as "decent pot" on Casual+. On Practice it's impossible without multiple all-ins (whole buy-in is 200) — but Practice is rare in V1 flow and the chip threshold being out of reach on the lowest tier doesn't actively misread. | V1 — no change |
| `POT_5000` | EITHER | EITHER | No | ~~Broken on Challenging~~ — re-anchored to ≥ 25× BB via a new `MAX_POT_BB_RATIO` counter that runs alongside the absolute `MAX_POT_SEEN` (which still backs POT_500 / POT_1000). Tier-agnostic: 250 chips on Practice, 5,000 on Standard, 25,000 on High, 50,000 on Premium. Same id; criterion + counter updated in [`AchievementRepositoryImpl.updateMaxPotSeen`](../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/AchievementRepositoryImpl.kt). | **Landed** |

### Tactical outcomes — fold / all-in

| Id | Current mode | Proposed mode | Split id? | Threshold rationale | Ship target |
|---|---|---|---|---|---|
| `FIRST_WIN_BY_FOLD` | EITHER | EITHER | No | "Won a pot without showdown" reads identically in either mode. Bluffing a human is more meaningful than bluffing a bot, but the criterion is the same and the EPIC-tier variants aren't on the table — the COMMON/RARE rows on this axis stay mode-agnostic. | V1 — no change |
| `WIN_BY_FOLD_10` | EITHER | EITHER | No | Same. | V1 — no change |
| `GOOD_FOLD_FIRST` | EITHER | EITHER | No | Hindsight-based ("folded a hand that would have lost at showdown") works the same against bots and humans. | V1 — no change |
| `GOOD_FOLD_25` | EITHER | EITHER | No | Same. | V1 — no change |
| `FIRST_ALL_IN` | EITHER | EITHER | No | Engagement milestone; mode-agnostic. | V1 — no change |

### Stack swings

| Id | Current mode | Proposed mode | Split id? | Threshold rationale | Ship target |
|---|---|---|---|---|---|
| `DOUBLE_UP` | EITHER | EITHER | No | Multiplicative on starting stack ("ended with 2× starting"), so tier-agnostic by construction. | V1 — no change |
| `TRIPLE_UP` | EITHER | EITHER | No | Same. | V1 — no change |

### Busting opponents — `Custom(BUSTS_DEALT)`

| Id | Current mode | Proposed mode | Split id? | Threshold rationale | Ship target |
|---|---|---|---|---|---|
| `FIRST_BUST_DEALT` | BOTS | BOTS | **Yes (V1.x)** | Bot-only at V1 by deliberate choice — the registry comment notes "the MP equivalents will land as separate ids when Phase 4.2 ships." Busting a human is meaningfully different (prestige, social weight) and deserves its own id, not a mode-toggle on the same one. **V1.x:** add `FIRST_BUST_DEALT_MP` + `BUST_DEALT_5_MP` (and probably `BUST_DEALT_25_MP` for the league flex) gated on `mode = MULTIPLAYER`. | V1.x — add MP-keyed siblings |
| `BUST_DEALT_5` | BOTS | BOTS | **Yes (V1.x)** | Same. | V1.x — add MP-keyed siblings |

### Level milestones — `Custom(CURRENT_LEVEL)`

| Id | Current mode | Proposed mode | Split id? | Threshold rationale | Ship target |
|---|---|---|---|---|---|
| `REACH_LEVEL_5`, `REACH_LEVEL_10`, `REACH_LEVEL_25` | EITHER | EITHER | No | Level is global state mirrored from progression; the criterion has no per-mode meaning. | V1 — no change |

## Summary of recommended V1.x changes

1. **`DONT_CALL_IT_COMEBACK`** — ✅ **Landed.** Re-anchored to BB multiples in [`AchievementRepositoryImpl.updateDontCallItComebackCounter`](../libraries/cards/impl/src/commonMain/kotlin/com/cards/libraries/cards/impl/AchievementRepositoryImpl.kt): arm when ending stack ≤ 10 BB (and > 0); fire when armed && ending ≥ 100 BB. Same id, criterion rewritten; description text refreshed. Existing users keep their unlock.
2. **`POT_5000`** — ✅ **Landed.** Switched to a new `MAX_POT_BB_RATIO` high-water counter (running alongside the absolute `MAX_POT_SEEN`); criterion is `MAX_POT_BB_RATIO ≥ 25`. POT_500 / POT_1000 keep reading the absolute counter (tier-tolerant by the threshold rationale above). Description text refreshed.
3. **`FIRST_BUST_DEALT_MP` / `BUST_DEALT_5_MP`** — pending Phase 4.2. New ids with `mode = MULTIPLAYER` and the same numeric thresholds as their bot siblings. Fire when the busted opponent's seat was a human. Counter key can be reused (`BUSTS_DEALT` ticks regardless of opponent type, gated by mode flag at check time) OR a new `MP_BUSTS_DEALT` key — favor the former so a future engine refactor doesn't have to migrate counters.

## What this audit does NOT do

- **Doesn't add net-new achievement concepts.** This is the audit, not a creative pass. New chains (e.g. "Bot Whisperer" capstone for beating every personality) live in the unlock-only-catalog seeding bullet in [todo.md](./todo.md), not here.
- **Doesn't decide ship-with-V1-or-V1.x for the MP-only ids.** Those are gated on Phase 4.2 (server-authoritative gameplay) landing — see [docs/decisions.md](./decisions.md). The proposed-mode column carries the recommendation; the actual ship is when MP achievements have a server-witnessed grant pathway.
- **Doesn't bless the V1 thresholds as final.** Calibration of XP rewards, chip rewards, and counter targets is a Phase-8 chip-economy modeling exercise per [product-spec.md](./product/product-spec.md). The audit accepts the existing numbers as the starting point and only flags the ones that are *structurally* broken (absolute-chip thresholds across stake tiers).
