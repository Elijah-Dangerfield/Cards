# TODO reference mocks

Temporary holding spot for design references attached to items in
[`../todo.md`](../todo.md). Drop the actual screenshots here under the
filenames below; the textual descriptions are the source of truth until the
PNGs land (the descriptions were transcribed from the originals).

Delete an entry once its todo ships.

---

## `stats-style.png` — player play-style blob (Stats)

A card titled **"Your style"** (top-left), with **"Equipped ✓"** in green
top-right *(ignore the equipped affordance)*.

- A square panel containing a 2×2 grid with axis labels: **AGGRESSIVE** (top),
  **PASSIVE** (bottom), **TIGHT** (left), **LOOSE** (right), faint crosshairs
  through the center.
- A soft, organic **orange blob** (radar shape) filling the upper-middle of the
  grid — biased toward the TIGHT + AGGRESSIVE quadrant — with a small white dot
  at the center.
- Below the panel: an orange pill reading **"Tight-Aggressive"**.
- Caption: *"You play **few hands** but bet them **hard**. Opponents who see
  this on your seat will respect your raises."*

## `recent-xp.png` — recent XP rows

Header **"Recent XP"** (left) · **"This week"** (right, muted). Two rows, each a
dark rounded list-row:

1. 🏆 (gold trophy emoji tile) · **"Won a hand vs Theo"** / sub: *"Two pair ·
   1.2k pot · 2h ago"* · trailing **+34** in **green**.
2. 🎯 (red/coral target emoji tile) · **"Achievement · Big bluff"** / sub:
   *"7-high … showdown · yesterday"* · trailing **+50** in **gold/amber**.

Takeaways: leading source emoji, `+N` colored by XP source, and a relative
"when earned" timestamp in the subtitle.

## `level-up-screen.png` — full-screen level-up celebration

> **Note:** the PNG isn't in the repo yet — drop the screenshot here under this
> filename. This transcription is the source of truth until it lands.

A full-screen, dark celebration (no top bar):

- Top: a small **teal uppercase eyebrow** — **"LEVEL UP"**.
- Center hero: a **teal sunburst** (the DS `RotatingDial`, re-skinned teal)
  radiating behind a **glowing teal coin/disc** with the new level number
  (**"7"**) in white. Confetti flecks (teal / gold / coral) scattered around.
- Below the hero: an **italic serif teal headline** = the level's *name*
  (**"Calculated"**). *(Per-level names are future content — see backlog; V1
  may omit this line.)*
- Subtitle (muted): *"Level 7 reached. You read the table better than 88% of
  players."* *(The percentile half needs server distribution data — future;
  V1 uses a warm generic line.)*
- An **"Unlocked" callout card** (dark rounded row): a small gold crown icon
  tile, a teal **"UNLOCKED"** label, title **"Ranked tournaments"**, sub
  *"Compete in the Royal Flush"*. *(Level-gated unlocks don't exist yet —
  future; the slot is hidden when there's nothing to unlock.)*
- Bottom: a full-width **teal "Continue"** button.

Takeaways: keep the **teal / progression** color identity (level + XP already
use `accentSecondary` / `LevelProgressGradient`); reuse `RotatingDial` for the
burst; haptics + entrance animation on reveal. The name line, percentile, and
unlock callout are aspirational (need data) — V1 ships the burst + level number
+ generic line + Continue.

## `hand-rankings.png` — in-game "What beats what" dialog (two mocks)

Two phone frames. **The right frame is the target.**

- **Right (target):** a dark bottom-sheet. Top **summary banner** (dark green)
  showing the user's cards — `A♠ A♥ 9♦ 9♣ K♣` — with **"YOU HAVE / Two Pair"**
  (green) on the right. Below: a small **"WHAT BEATS WHAT"** section label, then
  a tight numbered list 1–10 (Royal Flush → High Card), each row = rank number +
  hand name + a **compact** row of card glyphs. The user's current hand row —
  **8 · Two Pair** — is **highlighted green**.
- **Left (current-ish, for contrast):** full screen, amber italic header
  *"What beats what"*, numbered list with hand name + description + larger card
  images, "Two Pair" row highlighted green with a "YOU" badge.

Takeaways vs. today: add the you-have banner, render cards tighter, highlight
the current hand, keep the plain "What beats what" heading.
