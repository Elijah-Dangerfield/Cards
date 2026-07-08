# In-flight log

Per-commit handoff notes for tonight's cycle. The reviewer reads these when writing the PR, then deletes the file.

## fix(room): reveal the showdown before the bust dialog in solo play (GAME-18)

**Problem:** Busting at showdown against bots popped `BustDialog` straight over the table, so the player never saw the hand they lost to — the card reveal played under the scrim (feedback case 53e19e0438c84ebda67ad88133e8f79d).
**Approach:** Sequenced the existing `ShowdownDialog` first (CTA swapped to "Continue" via a new `ctaText` param), then the bust dialog on acknowledge; the bust dialog drops the XP/achievement rows when the reveal already showed them. Rejected the alternative of embedding the result inside `BustDialog` — it would duplicate the showdown layout and crowd the recovery moment. Fold-out busts (nothing to reveal) go straight to the bust dialog.
**Reviewer notes:** Red-first Compose UI test in `PlayPokerScreenTest` (`soloBustAtShowdown_showsRevealBeforeBustDialog`) reproduces the bug, plus a fold-out guard test. Real-MP busts keep `MultiplayerBustDialog` untouched — the reveal-first sequencing there needs the rebuy-grace countdown thought through.
**Deferred:** Real-multiplayer bust path still replaces the showdown reveal with `MultiplayerBustDialog`; same UX gap in principle but interacts with the rebuy-grace countdown — nothing filed yet, reviewer please triage.
