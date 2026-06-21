-- V61: Friend graph — the social-relation table behind the friends strip,
-- the recently-played-with add-friend flow, and the requests inbox.
--
-- One row per *unordered pair* of users. `user_a` is always the
-- lexicographically smaller UUID and `user_b` the larger, so a relation is
-- unique regardless of who initiated it — there is never both an (x, y) and a
-- (y, x) row. The application layer canonicalises the pair before every read
-- and write.
--
-- `state` is the lifecycle of the pair:
--   requested — a pending friend request awaiting the other side.
--   accepted  — a mutual friendship.
--   blocked   — one user has blocked the other; dominates request/accept.
--
-- `acted_by` carries the direction the canonical pair can't: the user who put
-- the row in its current state. For `requested` it is the *sender* (the other
-- member is the inbox recipient); for `blocked` it is the *blocker*; for
-- `accepted` it is whoever completed the acceptance (informational). Listing a
-- user's inbound requests is therefore "rows where I am a member, state =
-- requested, and acted_by is the other member".
--
-- FK to auth.users ON DELETE CASCADE per the V11 convention: a Supabase-side
-- delete cascades a user's relations (both as user_a and user_b). The app-level
-- DELETE /v1/me cascade also clears them explicitly so a mid-cascade crash
-- can't strand half a pair.
--
-- CHECK enforces the canonical ordering at the DB level so a mis-ordered insert
-- (an application bug) fails loudly instead of creating a duplicate-direction
-- row the unique PK wouldn't catch.

CREATE TABLE friend_relations (
    user_a      UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    user_b      UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    state       TEXT NOT NULL CHECK (state IN ('requested', 'accepted', 'blocked')),
    acted_by    UUID NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_a, user_b),
    CHECK (user_a < user_b)
);

-- A user's relations are read from either side of the pair, so index user_b
-- too (the PK already covers user_a as its leading column).
CREATE INDEX friend_relations_user_b_idx ON friend_relations (user_b);
