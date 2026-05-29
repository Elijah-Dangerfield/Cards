# In-flight (current cycle)

This file is ephemeral — one block per worker commit this cycle. The reviewer reads it when writing the PR description, then deletes the file as part of opening the PR.

## feat(catalog): pair SHOW_FULL_HOUSE with title_full_boat

**Problem:** §A "Catalog gating — More earnable cosmetic pairings" called out `SHOW_FULL_HOUSE` and `SHOW_FOUR_OF_KIND` as the remaining unpaired EPIC showdowns. After V42 every rarity-EPIC-or-above achievement *except* those two had a cosmetic complement, breaking the showdown-ladder symmetry that V41/V42 established for the two LEGENDARY mysteries.
**Approach:** seeded `title_full_boat` via V44 (server SQL migration following the V35/V41/V42 unlock-only-title pattern — `unlock_only=TRUE`, `is_equippable=TRUE`, sort 790 just below `title_suited_run`); wired client-side via `cosmeticRewardFor(SHOW_FULL_HOUSE)`, `titleForProductId("title_full_boat")`, and `ClientGrantableAchievements.Default.clientGrantable`. Picked "Full Boat" over "Housemaster" / "Boat Captain" / literal "Full House" — "boat" is universal poker slang for a full house and the two-poker-word shape mirrors V42's "Suited Run" without repeating the literal hand name. Updated `EarnableCosmeticsTest` (added the new mapping, moved `SHOW_FOUR_OF_KIND` into the no-reward null-check spot vacated by `SHOW_FULL_HOUSE`); added `GrantsRoutesTest.defaultPolicy_grantsFullBoatTitle_forShowFullHouse` mirroring the V41/V42 test shape.
**Reviewer notes:** the name itself is the directional call worth a second eye — "Full Boat" reads natural to poker players but might look unfamiliar to a brand-new user the first time it lands under their seat. Easy revert (one DB row + four when-branch entries + two test pins) if the reviewer wants a different word.
**Deferred:** the matching `title_quartet` for `SHOW_FOUR_OF_KIND` ships in the next commit this cycle, so they roll together as one PR but each gets its own logical commit.

