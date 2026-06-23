# In-flight (2026-06-23 cycle)

## fix(server): hard-delete users on account deletion

**Problem:** `DELETE /v1/me` called Supabase admin delete-user with no body, so GoTrue soft-deleted by default — the `auth.users` row stayed, the credential kept authenticating, and the design's `ON DELETE CASCADE` never fired (a tester re-logged-in after "deleting"). CARDS-1T.
**Approach:** `HttpSupabaseAdminClient.deleteUser` now sends `{"should_soft_delete": false}` as a JSON body on the DELETE, forcing GoTrue to hard-delete the row + cascade. Added a body assertion to the success test (the existing tests only checked URL/headers).
**Reviewer notes:** None — body shape matches the GoTrue admin API. Can't end-to-end verify against a live Supabase from here; covered by the MockEngine body assertion.

## feat(rooms): gate private room join on wallet balance

**Problem:** `POST /v1/rooms/{code}/join` had no wallet-vs-buy-in check (unlike matchmaking's find path), so a tester joined a room whose buy-in exceeded their chips and was silently demoted to spectator by escrow. CARDS-1G.
**Approach:** Server gate added to the join route — fetch the room, and if the caller isn't already a member and `room.buyIn > balance`, reject `400 insufficient_balance` (mirrors `MatchmakingRoutes`; re-joins skip the check since they already sat down). Client maps the code to a new `JoinRoomOutcome.OverBalance(message)` → `LobbyError.JoinOverBalance`, surfacing the server's message inline like `CreateInvalidMaxSeats` does (no new string resource needed). Tests on both sides.
**Reviewer notes:** The over-balance message is rendered server-side and shown verbatim (server-supplied copy is the AGENTS.md exception to the resources rule, same as `CreateInvalidMaxSeats`). Unknown codes still fall through to join()'s 404 — the gate only fires when the room exists.
