# Architecture — the live backend stack

The stack as built. For why each call was made, see [`../decisions.md`](../decisions.md). For client patterns (sync, refresh, buses) see [`client-patterns.md`](./client-patterns.md). For multiplayer wire/architecture see [`multiplayer.md`](./multiplayer.md).

## Stack

| Layer | Choice |
|---|---|
| Auth | Supabase Auth — anonymous sign-in on first run; Apple / Google account claim (`ClaimAccountScreen`) is the durable-identity path |
| Database | Supabase Postgres — direct IPv6 in prod, Session Pooler in local dev |
| Application server | Ktor 3.x on JVM 17, deployed to Fly.io |
| Realtime (in-game) | Ktor WebSockets, server-authoritative — **not** Supabase Realtime |
| Server DI | kotlin-inject + anvil (same as client) |
| Server query layer | Exposed + HikariCP + Flyway migrations |
| Server integration tests | Testcontainers + real Postgres (not mocked) |
| Hosting (server) | Fly.io — `cards-server-dev`, future `cards-server` |
| Secrets (server) | `fly secrets set …` in prod; `apps/server/.env` (gitignored) in local dev |
| CI deploy | GitHub Actions on merge to `main` when `apps/server/**` changes |
| Crash + error reporting | Sentry (client + server) |
| Tracing | OpenTelemetry → Grafana Cloud (Tempo for traces, Loki for logs) |
| Avatar storage (future) | Supabase Storage |

## The client / server boundary

**The client talks to two services:**

1. **Supabase Auth** — sign-in, sign-up, token refresh, account linking. Uses `supabase-kt` directly. No proxy through our server.
2. **Our Ktor server** — everything else: profile, game state, chips, XP, achievements, room create / join, future leaderboards. Authenticated with the Supabase-issued JWT.

**The server talks to two services:**

1. **Supabase Postgres** — direct JDBC, full SQL access via service-role credentials. The service role bypasses RLS, which is why we can enable deny-all RLS for PostgREST safety without breaking anything.
2. **Supabase Admin API** — only for account-deletion compliance + user lookups (future).

In-hand state lives in a server-side coroutine and is fanned out over **our** WS — Supabase Realtime is not part of the gameplay path.

**Why this split** (and not "everything through our server"): profile / chips / XP / games are state-of-record and must flow through Ktor. Auth JWTs are *capabilities*, not state — letting the client talk to Supabase Auth directly is the standard pattern and saves rebuilding OAuth flows.

## Single-writer hosting model

The server runs as **exactly one instance**, and that's enforced, not just assumed. Live room and hand state lives in this process's RAM (`GameSessionRegistry` holds the game sessions, `InMemoryRoomService` the rooms), with Postgres keeping durable snapshots for restart recovery. Both services rehydrate a room from its snapshot on a lookup miss, so two concurrent instances would each build a *divergent* in-memory copy of the same table — a split-brain that loses bets and desyncs seats.

`SingleWriterGuard` closes that door: at boot, before any routes are wired, the server takes a Postgres **session-level advisory lock** on a fixed slot and holds it for the life of the process. A second instance (a stray `fly scale count 2`, an added region, a blue-green deploy running old and new together) fails the acquire and refuses to boot — it crash-loops loudly instead of quietly corrupting tables. The lock rides its own dedicated JDBC connection, so when the owning process exits (cleanly or by crash) Postgres frees it and the next instance acquires cleanly — exactly the handoff a rolling redeploy needs.

Scaling past one instance (shard rooms by code, move matchmaking queries into Postgres) is deliberately deferred until the load demands it. See [`../decisions.md`](../decisions.md) (2026-07-03).

## Remote configuration

Server config is exposed via `GET /v1/app-config` and reads typed values from an app-config tree (`identity.*`, `progression.*`, etc.). The client's `:libraries:config` reads the tree through `AppConfigMap`. Scalar values use `BooleanConfigValue` / `LongConfigValue` / `StringConfigValue`; structured payloads (the XP curve, the level→reward table) ride a `JsonConfigValue` extension and decode into typed Kotlin models.

A change to a tunable value lands by editing the config tree on the server — **no client release required**. The client ships a bundled default so it works offline and on the very first launch.

The progression ladder (XP-per-level curve + level→reward table) is the canonical case: the client grants level rewards offline by stable idempotency keys, and the server reconciles against its own copy of the same config in the progression-sync response. See `wiki/progression.md` for the full flow.

## Cost model at V1 scale

- **Supabase free tier:** 500 MB DB, 50k MAU, 5 GB egress — covers V1 launch.
- **Fly.io:** ~$5-15 / mo for the smallest shared-cpu-1x; free hobby tier covers dev.
- **Realistic V1 launch total:** ~$25 / mo (Supabase Pro $25 if we hit free-tier ceiling).
