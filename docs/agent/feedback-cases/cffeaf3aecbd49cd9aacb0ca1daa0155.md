# Feedback case cffeaf3aecbd49cd9aacb0ca1daa0155

- **Sentry issue:** https://elijah-dangerfield.sentry.io/issues/CARDS-3Z (primary); CARDS-40 + CARDS-45 same root cause
- **Reported:** 2026-06-25T14:52:23Z · FeedbackRoute · dev-ios-debug · cardse@0.1.0+1
- **Disposition:** todo: "MP-15 — public matchmaking opens a fresh room instead of joining an existing open one"

## Bug description
> (CARDS-3Z) on another simulator I made a game and marked it as open. But on this device I found no rooms available to join. The code is TVY6KQ
> (CARDS-40) well after trying again it found the room. WTF
> (CARDS-45) I clicked to join a game from the list. On the other device I see the user added to the lobby. But now my screen is just saying its searching.

## IDs
- user: 7715a976-cbbe-426c-a0d4-d566b57fa5f8 (LuckyJack32, the searcher)
- session: 3623bbff-a139-4fbb-89f9-65485f43b67f
- install: 547247dc-a471-47c0-9d9a-719eb9bc14a9
- opponent / room host: BoldJack37 5eb3a5c8-71a7-441e-bdae-77969b62e753 (session 0066cd72-...), created+opened room TVY6KQ

## Reporter client log
```
14:51:06 navigate to PublicFindRoute
14:51:11 navigate to PublicSearchingRoute
14:51:11 RoomSocket: Room socket connected (code=5DW85H)   <-- a NEW room, not TVY6KQ
14:51:11 recv snapshot members=1                            <-- sitting alone, "searching"
14:52:27 NavigateBack  → 14:52:28 PublicSearchingRoute again
14:52:43 RoomSocket: Room socket connected (code=TVY6KQ)    <-- finally in the existing room
14:52:43 recv snapshot members=2
```

## Server activity
- Loki: BoldJack's socket connected to TVY6KQ at 14:51:05. At 14:51:11 LuckyJack's `POST /v1/matchmaking/find` returned 200 but the server `Matchmaking opened public room 5DW85H at buy-in 1000 for 7715a976-...` (findOrJoinPublic, InMemoryRoomService.kt:276) — it created a brand-new room rather than matching him into the already-open TVY6KQ. Repeated `GET /v1/matchmaking/candidates` (14:52:29/34/39) precede the eventual direct `POST /v1/rooms/TVY6KQ/join` (14:52:43).
- Also seen (unrelated noise): `GET /v1/matchmaking/subsidy-budget 404` on the client — endpoint missing on the dev server.

## Working theory
The public-matchmaking `find` path (`InMemoryRoomService.findOrJoinPublic`) did not surface BoldJack's open room TVY6KQ — it spun up a fresh empty room (5DW85H) and left the searcher sitting alone on the "searching" screen. The room only got joined once LuckyJack backed out and joined by code directly. Root cause is in the find/candidates matching: either the just-opened room isn't eligible yet (visibility/eligibility filter or an indexing race in the in-memory room registry), or `find` prefers opening-new over joining-existing. Public matchmaking shipped in #67; this is a discoverability regression/gap. Fix: `find` should join an eligible existing open room before creating a new one, and `candidates` should include a freshly-opened open room without a delay. Add a test: A opens a public room → B's `find` lands in A's room (members=2), never a new room. High confidence (server log shows it opening 5DW85H instead of joining TVY6KQ).
