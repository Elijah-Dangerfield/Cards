## refactor(room): migrate PlayPokerScreen chrome strings to resources

**Problem:** The `:features:room:impl/PlayPokerScreen.kt` chrome still held four inline user-facing strings — connection-lost banner, back-icon a11y, cheat-sheet question-icon a11y, "Dealing in…" loading copy — keeping the strings-sweep §A item one screen short of finishing the play surface.
**Approach:** Lifted the four to `:libraries:resources/strings.xml` under `room_connection_lost_banner` / `room_top_bar_back_a11y` / `room_top_bar_hand_info_a11y` / `room_loading_dealing_in`, resolved via `stringResource(...)` at the existing callsites. `OpponentsRow.kt` was scanned at the same time and carries no user-facing strings beyond the "▼" chevron typography character — noted in the todo so a future cycle doesn't re-derive that.
**Reviewer notes:** None — straight one-to-one swap, build green via `./gradlew :apps:compose:assembleDebug`.
