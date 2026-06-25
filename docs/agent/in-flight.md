# In-flight (this cycle)

## fix(lobby): route Leave button through confirm + back (ROOM-2)

**Problem:** The in-room "Leave room" button dropped the seat server-side but left the user stranded on the now-idle lobby — only the top back arrow both left and navigated away.

**Approach:** The Leave button fired `onAction(LobbyAction.Leave)` alone; `Leave` only resets the VM to idle, it emits no navigation event. The back/OS-back path routes through `requestBack`, which confirms then fires both `Leave` and `onBack()`. Pointed the Leave button at that same `requestBack` so all three affordances (top-bar back, OS back, Leave button) share one path: confirm → notify server → navigate out. The Leave button now also gets the leave-confirm dialog it previously skipped, which matches the table-leave UX.

**Reviewer notes:** UI-wiring change only — the VM's `Leave` handling is unchanged and already covered by `LobbyViewModelTest`. Not unit-testable below the Compose layer; the project verifies lobby states via `@Preview`. Verified `:apps:compose:assembleDebug` builds. Added QA `MP-10`.

## feat(ui): mark the local player's seat with a "you" caption (ROOM-3)

**Problem:** Nothing explicitly labelled which seat in the lobby grid is you — only a subtle accent ring/name colour, easy to miss.

**Approach:** `RoomSeatPlayer.isYou` already flows through from `LobbyScreen.toSeatPlayer`. Added a "you" caption line under the name in `RoomSeat`'s filled-seat layout, slotted into the same caption `when` as "joining…"/"up next" so it never collides with the HOST/BOT badge — a host who is also you keeps the HOST badge and gains the "you" caption. New string `room_seat_you_label` in `:libraries:resources`. Chose a caption over a third badge because the badge slot is already an if/else between HOST and BOT; a caption is unambiguous and additive.

**Reviewer notes:** Covered by the existing `RoomSeatPreview` (seat 0 is `isYou = true`). The component's sibling captions ("joining…", "up next") are still hardcoded literals predating this change — I only routed my new string through resources rather than migrating theirs (out of scope; would belong with ENG-2's `verifyStrings` cleanup).

## MP-16 — investigated, not shipped (left in todo.md)

**Finding:** The todo's stated fix ("seed the create-room buy-in slider to a non-zero default") is already in place — `PrivateCreateScreen` seeds `buyIn` to `RoomSettings.DEFAULT_BUY_IN` (5000) and clamps to `MIN_BUY_IN` (100); the slider can never reach 0 (seeded since #62, before the 2026-06-25 playtest). The server also already rejects buyIn=0 with a 400 `invalid_buy_in` (`RoomRoutes.kt:79`), and `Room.toDto()` echoes the real buy-in. So a $0 room cannot be persisted, and both reporters' `POST /v1/rooms` returned 200 (valid buy-in accepted).

The "$0 in the lobby" the testers saw is therefore a *display* artifact, not a persisted value — most likely the lobby briefly rendering `room.buyIn` (DTO default `= 0`) from a transient/partial snapshot before the live room snapshot lands. I could not pin the exact snapshot path without runtime traces, and a speculative fix to the buy-in display/snapshot risks churn. Left MP-16 in `docs/todo.md` unchanged rather than rewrite its premise on an unconfirmed theory — flagging for the human: the real gap is the lobby showing 0 from a default-valued snapshot, NOT the slider seed.

**Deferred:**
- PROG-1 (achievement engine → PlayerStats predicates): a live-unlock-path refactor touching grants + a new `claimed_at_value` per achievement; only ~5 of ~25 counters have a `PlayerStats` equivalent, so a clean conversion is partial and a wrong call re-fires/wipes achievements. Needs a human design call before touching the grant path — left for the human / a dedicated cycle.
- MP-15 (find-vs-create): root cause is most likely a buy-in-range mismatch between user-created Open rooms (arbitrary buy-in) and the matchmaker's tier-snapped search windows — resolving it is a product call on how Open-room buy-ins map to matchmaking tiers, which affects the buy-in matching path. Left in todo.md.
