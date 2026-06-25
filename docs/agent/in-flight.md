# In-flight (this cycle)

## fix(lobby): route Leave button through confirm + back (ROOM-2)

**Problem:** The in-room "Leave room" button dropped the seat server-side but left the user stranded on the now-idle lobby — only the top back arrow both left and navigated away.

**Approach:** The Leave button fired `onAction(LobbyAction.Leave)` alone; `Leave` only resets the VM to idle, it emits no navigation event. The back/OS-back path routes through `requestBack`, which confirms then fires both `Leave` and `onBack()`. Pointed the Leave button at that same `requestBack` so all three affordances (top-bar back, OS back, Leave button) share one path: confirm → notify server → navigate out. The Leave button now also gets the leave-confirm dialog it previously skipped, which matches the table-leave UX.

**Reviewer notes:** UI-wiring change only — the VM's `Leave` handling is unchanged and already covered by `LobbyViewModelTest`. Not unit-testable below the Compose layer; the project verifies lobby states via `@Preview`. Verified `:apps:compose:assembleDebug` builds. Added QA `MP-10`.

## feat(ui): mark the local player's seat with a "you" caption (ROOM-3)

**Problem:** Nothing explicitly labelled which seat in the lobby grid is you — only a subtle accent ring/name colour, easy to miss.

**Approach:** `RoomSeatPlayer.isYou` already flows through from `LobbyScreen.toSeatPlayer`. Added a "you" caption line under the name in `RoomSeat`'s filled-seat layout, slotted into the same caption `when` as "joining…"/"up next" so it never collides with the HOST/BOT badge — a host who is also you keeps the HOST badge and gains the "you" caption. New string `room_seat_you_label` in `:libraries:resources`. Chose a caption over a third badge because the badge slot is already an if/else between HOST and BOT; a caption is unambiguous and additive.

**Reviewer notes:** Covered by the existing `RoomSeatPreview` (seat 0 is `isYou = true`). The component's sibling captions ("joining…", "up next") are still hardcoded literals predating this change — I only routed my new string through resources rather than migrating theirs (out of scope; would belong with ENG-2's `verifyStrings` cleanup).

## fix(lobby): hide lobby stakes row until buy-in hydrates (MP-16)

**Problem:** Testers saw a flashed "$0 buy-in" in the lobby even though the room was created with a real buy-in.

**Approach:** Every wire path (`POST /v1/rooms`, join, the socket `Snapshot`, the active-rooms list) already carries the real buy-in via the server's `Room.toDto()`, and the slider seed + server `buyIn=0` reject are already correct (confirmed last cycle) — so a "$0" in the lobby is *always* a not-yet-hydrated snapshot (`RoomDto.buyIn`/`Room.buyIn` both default to 0), never a valid game. Rather than rearchitect snapshot hydration on an unconfirmed source (last cycle couldn't pin which path leaks the 0 without runtime traces), I guarded the display: `InRoomContent` now renders the stakes/buy-in Row only when `room.buyIn > 0`. Suppressing the whole Row (not just the buy-in card) keeps the two-card layout honest. The source-pinning work is the remaining MP-16 slice (kept in todo.md, rewritten).

**Reviewer notes:** Not unit-tested below Compose (lobby states are verified via `@Preview`, matching the existing convention). Verified `:apps:compose:assembleDebug` builds. The smallBlind/bigBlind also default to 0 and ride the same Row, so they're covered by the same guard. Added QA MP-11.

**Deferred:**
- PROG-1 (achievement engine → PlayerStats predicates): a live-unlock-path refactor touching grants + a new `claimed_at_value` per achievement; only ~5 of ~25 counters have a `PlayerStats` equivalent, so a clean conversion is partial and a wrong call re-fires/wipes achievements. Needs a human design call before touching the grant path — left for the human / a dedicated cycle.
- MP-15 (find-vs-create): two distinct root causes confirmed across the three Sentry IDs, both needing a product call before a safe fix, so left in todo.md.
  - CARDS-3Z/40 ("found no rooms", then found it on retry): the buy-in-range mismatch. A user-created Open room carries the host's arbitrary buy-in, but the searcher's range is the slider band; `matchmakingCandidates` filters `room.buyIn in minBuyIn..maxBuyIn` and the skip is *deliberate + test-locked* (`MatchmakingServiceTest.find_outOfRangeRoom_isSkipped`). So relaxing it is a product decision on how Open-room stakes map to matchmaking windows, not a bug-fix.
  - CARDS-45 ("clicked to join from the list ... screen still says searching"): the join-from-chooser path strands the user. `PublicSearchingViewModel.joinAndWatch` joins the picked room then `watchRoom`, but `ConnectionUpdated` only navigates when `room.status == Playing` (`PublicSearchingViewModel.kt:183`). An Open room the host created sits in `Lobby` until the server deals it; if it never flips (host hasn't started / not enough humans yet to auto-deal), the joiner is stuck on "searching" forever with no path into the lobby. Fix needs the intended Open-room entry flow decided: should a chooser-join drop the user into the seated lobby (like a private join) rather than the searching screen, or should joining an Open room trigger the server deal? Both touch the Open-room product contract.
