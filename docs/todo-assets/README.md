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
