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

## feat(room): surface rejected next-hand on heads-up bust (MP-14)

**Problem:** When the heads-up loser busts to 0, the server correctly rejects the winner's "next hand" with `not enough players with chips for next hand`, but the client sent that frame fire-and-forget and dropped the rejection — the winner's tap vanished silently with no feedback.

**Approach:** `RemotePokerSession.requestNextHand()` stays fire-and-forget to the caller (keeping its conflate-rapid-taps semantics and avoiding a wide `suspend`-signature ripple into the ~15 integration/server call sites), but the pump now registers a pending ack for the frame and watches it the same way `submit()`/`rebuy()` do. On a rejection it fans out a new `PokerSession.nextHandUnavailable` flow (mirrors the `opponentLeft` SharedFlow pattern); the VM relays it as `PlayPokerEvent.NextHandUnavailable`, and the MP entry point toasts "waiting for your opponent to rebuy or leave". The busted player already has the rebuy/buy/leave dialog, so this closes the silent-no-op half of MP-14. Chose surfacing-via-flow over making `requestNextHand` suspend because the latter changes a contract used by many call sites for no extra value here.

**Reviewer notes:** This is a *slice* of MP-14 — the remaining gap (a terminal match-over resolution when the loser never rebuys) is a product call and needs a server-side match-over signal that doesn't exist yet; MP-14 is rewritten to describe that. Tested in `RemotePokerSessionTest` (emit-on-reject + no-emit-on-accept); the existing fire-and-forget next-hand tests still pass unchanged. A missing ack just times out quietly (prior behaviour). Added QA MP-8 sub-bullet.

**Deferred:**
- MP-13 (wallet conservation): the server settlement path (`DefaultTableSessionService` + `WalletLedger`) is already conservation-safe and keyed-idempotent, and a full-game conservation test already exists (`ChipEconomyPlayTest.fullHandThenBothLeave_conservesEveryChip`). The reported 22000-from-20000 mint must come from a stale/wrong `finalStack` at cash-out or a path that test doesn't exercise, which I couldn't pin without runtime traces — and it's a money flow, an explicit skip-reason. Left in todo.md.
