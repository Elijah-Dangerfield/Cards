## fix(server): drop "only in solo games" framing from felt catalog copy

**Problem:** V5's seed for `felt_royal_red` described the felt as "visible to you only in solo games", implying it was broadcast to other seats in MP. The 2026-05-20 decision locked felts as private (owner-only render), so that framing is wrong.

**Approach:** Added `V7__felt_private_copy.sql` that `UPDATE`s the one row whose description carried the broadcast-implying clause. V5 was shipped (in a recent commit, but possibly run on dev DBs), so editing it in place would be a Flyway checksum hazard — a forward migration is the safer pattern. Verified the audit half of the todo by tracing `EquippedFelt` through the room VM: the equipped felt is sourced from the local `equipmentRepository.observeEquipped()`, lives only on the screen state, and never appears in `:libraries:gameplay` / `:libraries:game` / server payloads. Render path is already local-only.

**Reviewer notes:** Only `felt_royal_red` had the offending clause — the other felts/themes (`felt_midnight_blue`, `felt_charcoal`, `table_neon`, `table_sunset`, `felt_sunset_weekend`) already read cleanly. `PostgresProductCatalogSourceTest.read_chipOffer_carriesDescription_whenPresent` asserts `contains("Deep red felt")`, which the new description still satisfies.

## fix(profile): hide Sound option from turn-feedback picker until audio lands

**Problem:** The Profile → Gameplay → "Your turn feedback" picker exposes Sound / Vibrate / Mute, but Sound is a no-op — the KMP audio path isn't built (tracked in backlog.md → audio infrastructure). New users get the default `Sound`, which silently gives them no cue at all.

**Approach:** Four touch points, all in service of a single user-visible change ("Sound disappears from the picker, behavior matches what the dropdown advertises"):
- `AppData.turnFeedback` default flipped to `Vibrate` so new users land on a working cue.
- `ProfileScreen` filters `TurnFeedback.Sound` out of the dropdown options and coerces the displayed label to `Vibrate` for legacy users whose stored value is still `Sound` (so the trigger doesn't show an option that isn't in the list).
- `PlayPokerScreen` consumer treats `Sound` as a vibrate haptic, matching the picker's display promise so legacy users actually get the feedback the UI advertises.
- `PlayPokerViewModel.State.turnFeedback` default mirrors `AppData`; test default updated.

Kept `Sound` in the enum (rather than removing it) because persisted JSON may carry it for existing users and removing the variant would fail deserialization — `Sound` is documented as the legacy value and lives behind a single helper (`pickerDisplayValue`).

**Reviewer notes:** Considered a versioned-cache migration to flip stored `Sound` → `Vibrate` at read time, but `versionedJsonSerializer` works at whole-`AppData`-version granularity. Adding a version just to coerce one enum felt heavier than the four-touch coercion approach. Worth revisiting if `AppData` gets a real version bump later.

**Deferred:**
- `ProfileScreen.kt` has an existing pattern of fully-qualified `com.dangerfield.cards.libraries.cards.TurnFeedback` references rather than imports — I followed the local style. Reviewer: nothing for you to do, just noting.

## feat(home): note that bot tables don't move chip balance on the setup dialog

**Problem:** `BotTableSetupDialog` (the seat-count picker that opens before a bots game) doesn't tell new users that bot tables are sandboxed — they may worry that losing a hand to a bot drains their chip balance.

**Approach:** Added a small note above the Start button: "Bot tables don't move your chip balance." Placed it directly above the CTA so the reassurance is the last thing the user reads before tapping. Tone matches the existing subtitle copy on the dialog (declarative, no exclamation marks, no "practice" — `voice-and-copy.md §4.1` explicitly rejects "Practice mode" as too clinical, so the todo's suggested phrasing was reworded to lead with "Bot tables" instead).

**Reviewer notes:** Voice check — `voice-and-copy.md` doesn't have a canonical line for this surface yet, so the wording is my call. Reviewer: if you want a stricter house style here, propose an alternate line and I'll swap it in a follow-up commit.
