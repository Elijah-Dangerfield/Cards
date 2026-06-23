# Deploying `cards-server-dev` to Fly.io

The first deploy is a one-time setup. After that, merging to `main` with
changes under `apps/server/**` triggers an auto-deploy via GitHub Actions
(see `.github/workflows/server-deploy.yml`).

## One-time setup

1. **Install flyctl** (if you don't have it):
   ```
   brew install flyctl
   ```

2. **Log in**:
   ```
   fly auth login
   ```
   Opens a browser. Sign up or sign in to your Fly account.

3. **Create the dev app**:
   ```
   fly apps create cards-server-dev
   ```
   (`cards-server` for prod, later.)

4. **Set secrets** (from the repo root):
   ```
   fly secrets set \
     DATABASE_URL='postgresql://postgres:nD58ubv82mzv%24EV@db.yuqrfhdoejonclgbixlw.supabase.co:5432/postgres' \
     SUPABASE_URL='https://yuqrfhdoejonclgbixlw.supabase.co' \
     -a cards-server-dev
   ```
   Notes:
   - `DATABASE_URL` uses Supabase's **direct connection** host (works from Fly because Fly has IPv6 outbound).
   - The `$` in the password is URL-encoded as `%24`.
   - `SUPABASE_URL` is enough on its own: the server fetches the project's public JWT signing keys from `<SUPABASE_URL>/auth/v1/.well-known/jwks.json` at runtime. No shared JWT secret is stored anywhere.
   - For account deletion, add `SUPABASE_SERVICE_ROLE_KEY` here (Project Settings → API → service_role key, treat like a root password).

   **Optional: error reporting via Sentry.**
   Once a Sentry project exists, add its DSN. Without these, the server logs a warning at startup and `Sentry.captureException(...)` becomes a no-op.
   ```
   fly secrets set \
     SENTRY_DSN='<https://...@oXXXX.ingest.sentry.io/YYYY>' \
     SENTRY_ENVIRONMENT='dev' \
     -a cards-server-dev
   ```
   For `cards-server` (prod), use `SENTRY_ENVIRONMENT='prod'` against the same Sentry project. Use one Sentry project for the server (separate from the client project) and let `SENTRY_ENVIRONMENT` differentiate dev vs prod issues — easier cross-env grouping than splitting projects.

5. **Get a deploy token for CI**:
   ```
   fly tokens create deploy -a cards-server-dev --expiry 8760h
   ```
   Copy the output. Paste it into GitHub: Repo → Settings → Secrets and variables → Actions → New repository secret → name `FLY_API_TOKEN_DEV`.

6. **First deploy**:
   ```
   fly deploy --config apps/server/fly.toml --remote-only
   ```
   `--remote-only` uses Fly's builders instead of local Docker (no Docker desktop required). Takes ~3-5 minutes for the first build; subsequent deploys reuse cached layers.

7. **Verify**:
   ```
   curl https://cards-server-dev.fly.dev/_health
   # {"ok":true}
   ```

## Anonymous user sweep (recommended once auth ships)

Anon Supabase users who sign in once and never come back stay in
`auth.users` forever — and on the OAuth-claim path, switching to a
pre-existing account orphans the anon row by design. The server exposes
a maintenance endpoint that lists + deletes anon users older than
`ORPHAN_ANON_TTL_DAYS` (default 30):

```
fly secrets set \
  ADMIN_API_TOKEN="$(openssl rand -hex 32)" \
  ORPHAN_ANON_TTL_DAYS=30 \
  -a cards-server-dev
```

Trigger once a day. Easiest path is a GitHub Actions cron in
`.github/workflows/sweep-anon.yml`:

```yaml
on:
  schedule:
    - cron: '17 5 * * *'   # 05:17 UTC daily — odd minute so we miss the top-of-hour stampede.
  workflow_dispatch:        # also runnable manually from the Actions tab
jobs:
  sweep:
    runs-on: ubuntu-latest
    steps:
      - run: |
          curl --fail-with-body -X POST \
            -H "X-Admin-Token: ${{ secrets.CARDS_ADMIN_API_TOKEN_DEV }}" \
            https://cards-server-dev.fly.dev/v1/admin/sweep-anonymous-users
```

(Equivalent prod workflow lives separately and points at
`cards-server.fly.dev`.) Response body shows `candidatesFound / deleted
/ failedToDelete`; a non-zero `failedToDelete` is worth investigating
in Sentry.

## Disconnected-room-member reaper (in-process)

Per-room WebSocket disconnect holds the member's seat in case the client
reconnects (mobile networks bounce all the time). The server schedules
a per-member reaper on every disconnect — `delay(grace)` then drop the
seat iff the member is still disconnected with the same stamp. A
reconnect (or a fresh disconnect) cancels the original reaper out
naturally because the stamp on the member no longer matches. The grace
is status-aware: a forming public/open lobby frees an abandoned seat in
~25s, a live hand keeps the full `DEFAULT_REAPER_GRACE` (5min); both
constants live in
[`RoomSocketRoutes.kt`](src/main/kotlin/com/cards/server/routes/RoomSocketRoutes.kt).

This in-process timer is the fast path, but it is **not** sufficient on
its own. Rooms are now persisted (the `rooms` + `room_members` registry,
B2) so codes + membership survive a restart. A process death takes its
in-flight reaper timers down with it, leaving the persisted seats — and
whole abandoned rooms — with no owner to reap them. The cron sweep below
is the durable backstop that reclaims them; wire it up before inviting
real users.

## Seat + orphaned-room sweep (required for durable cleanup)

`POST /v1/admin/sweep-rooms` (same `ADMIN_API_TOKEN` as the other admin
endpoints) does two things in one pass:

- Frees seats whose socket has been gone longer than the threshold —
  the seats a process death stranded past their in-process reaper.
- Deletes persisted rooms with no in-memory owner past the same
  threshold (the abandoned-after-restart leak). Live rooms are excluded
  by the in-memory check, so a long-running game is never swept out from
  under itself.

The threshold is `STALE_ROOM_TTL_HOURS` (default 6). Because live rooms
are protected regardless, this only governs how long a stranded room
lingers in Postgres before cleanup — it's safe to run as often as you
like:

```
fly secrets set \
  ADMIN_API_TOKEN="$(openssl rand -hex 32)" \
  STALE_ROOM_TTL_HOURS=6 \
  -a cards-server-dev
```

Trigger on a cron. The GitHub Actions workflow
[`.github/workflows/sweep-rooms.yml`](../../.github/workflows/sweep-rooms.yml)
calls it hourly at :41 (odd minute to dodge the top-of-hour stampede)
and surfaces the result in the run summary. The response carries
`membersReaped / roomsReaped / roomsSeen / orphanedRoomsReaped`; a
non-zero `orphanedRoomsReaped` after a clean run counts rooms the
in-process reaper never got to — expected after a deploy/restart, worth
a glance if it's persistently high.

## Inspecting live rooms (ops dashboard)

Same admin token gates `GET /v1/admin/rooms` — returns one summary per
live room (code, host, status, seat counts, connected vs disconnected).
Useful for verifying the sweep ran, spotting abandoned rooms between
cron ticks, and answering "how busy is MP right now."

```
curl -H "X-Admin-Token: $ADMIN_TOKEN" \
     https://cards-server-dev.fly.dev/v1/admin/rooms | jq
```

Output:

```json
{
  "rooms": [
    {
      "code": "AB3KP9",
      "hostUserId": "11111111-…",
      "createdAtEpochMs": 1715000000000,
      "status": "Lobby",
      "maxSeats": 6,
      "memberCount": 3,
      "connectedCount": 2,
      "disconnectedCount": 1
    }
  ]
}
```

No PII beyond what the lobby socket already exposes (display names live
on socket frames, not in this summary). Member-level detail is summary-
only — full member lists scale quadratically with concurrent rooms and
aren't needed for triage; jump to the lobby socket if you need names.

## Granting chips (production support)

When something goes wrong in prod and a user needs to be made whole —
chargeback refund, payout error, lost-chips ticket — the supported path
is `POST /v1/admin/grant-chips` behind the same `ADMIN_API_TOKEN`.

Don't curl it directly. Use the GitHub Actions wrapper:

1. Repo → **Actions** → **Admin · Grant chips (dev)** → **Run workflow**.
2. Fill the form: `userId` (Supabase auth UUID), `delta` (signed; negative
   debits), `reason` (free-form note for the audit log), optionally
   `idempotencyKey` (omit and the server fills one).
3. **Optionally attach an in-app dialog** by filling `messageTitle` +
   `messageBody` (both required if either is set). `messageEmoji`
   populates the dialog's bubble; `messageDeepLink` makes the CTA
   button open a URL instead of plain-dismissing. The dialog is
   scheduled only if the grant actually moves chips — a replay or
   insufficient-balance outcome doesn't double-attach.
4. Submit. The run's **Summary** tab shows the post-apply balance and
   the server's outcome (`Applied` / `AlreadyApplied` / `InsufficientChips`),
   plus the scheduled message id when applicable.

Why the wrapper instead of curl: `ADMIN_API_TOKEN` never leaves GitHub,
every grant is a workflow run tagged with the operator's GitHub identity,
and the input form validates the obvious typos before they hit the
server. Implementation: `.github/workflows/admin-grant-chips.yml`.

Ledger trail: each grant writes a `wallet_events` row with reason
`admin_grant:<your note>`. Filter the table with
`reason LIKE 'admin_grant:%'` to see every admin grant ever applied.

## Sending an in-app message (no chips)

For maintenance heads-ups, season launches, "we fixed the bug" support
outreach, or anything else that should reach the user but isn't a chip
event:

1. Repo → **Actions** → **Admin · Send message (dev)** → **Run workflow**.
2. Fill the form:
   - `userId` — Supabase auth UUID.
   - `kind` — **dialog** pops modally on next foreground (one per
     foreground; the user dismisses to see the next); **inbox** sits
     passively in the in-app Notifications screen with a bottom-bar
     badge until the user opens it.
   - `title` (max 80 chars), `body` (max 500 chars).
   - `emoji` (optional) — populates the bubble.
   - `deepLink` (optional) — turns the CTA / row tap into a navigate.
   - `expiresInDays` (default 30) — TTL after which the message stops
     being delivered. `0` = never expires.
3. Submit. The run's **Summary** tab shows the scheduled message id.

For broadcasts (a Christmas drop to everyone, an upgrade-required
notice to a specific cohort), call the workflow N times — V1
deliberately does NOT have a fan-out endpoint, so a single typo can't
spam the whole user base.

### When to pick dialog vs inbox

Lean **dialog** when the message is time-sensitive ("we're going down
in 1 hour"), celebratory ("you won the seasonal jackpot"), or wants
the user's hand on the screen before they keep playing.

Lean **inbox** when the message is informational ("we fixed the bug
that ate your hand last week"), a digest ("here's what's new this
patch"), or anything you're sending to a wide audience where dialogs
would feel pushy.

Chip grants attached via `admin-grant-chips` are always dialog — a
chip arrival is a moment, not a log entry. Send a separate inbox
follow-up if you want a permanent record.

## Storage + sweeping `user_messages`

Schema: one row per recipient. `kind` is `'dialog'` or `'inbox'`;
`acked_at` flips when the user dismisses (dialog) or views the
inbox screen with the row visible. The partial unread index
(`acked_at IS NULL`) keeps the hot read path tight even as history
grows.

`expires_at` is the absolute UTC cutoff. The unread-fetch endpoint
filters expired rows out so a stale notice never reaches the client
even if the sweep cron is lagging.

The nightly cron `Sweep expired messages (dev)` calls
`POST /v1/admin/sweep-expired-messages` at 04:23 UTC and purges:
  - Every acked row (regardless of age).
  - Unacked rows whose `expires_at` has passed.

A spike in `expiredUnackedPurged` is worth a look — it means users
weren't seeing their notices in time (TTL too short, or app
engagement dropped). The endpoint returns counts in the response;
the workflow surfaces them in the run summary.

### Rotating `ADMIN_API_TOKEN`

If the token leaks, rotate **both sides at once** — anything mismatched
breaks the sweeps. From the repo root:

```bash
NEW_TOKEN="$(openssl rand -hex 32)"
fly secrets set ADMIN_API_TOKEN="$NEW_TOKEN" -a cards-server-dev
gh secret set CARDS_ADMIN_API_TOKEN_DEV --body "$NEW_TOKEN"
unset NEW_TOKEN
```

The `fly secrets set` triggers a redeploy; the GitHub secret update
takes effect on the next workflow run. Verify by manually triggering
`Sweep disconnected room members` from the Actions tab — a 401 means
the values drifted.

## Multiplayer (Phase 4.1 — lobby + presence)

The room HTTP + WebSocket surface ships with the standard server image
— no extra Fly config needed. WebSocket upgrades work out of the box
behind Fly's edge proxy (it speaks HTTP/1.1 upgrade transparently).

To validate the WS path post-deploy:

```bash
# JWT-authenticated room create + join + observe via wscat:
TOKEN=$(...your fresh Supabase JWT...)
curl -X POST https://cards-server-dev.fly.dev/v1/rooms \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'
# → returns room.code, e.g. "AB3KP9"

# Open the socket — should immediately receive a Snapshot frame.
wscat -c "wss://cards-server-dev.fly.dev/v1/rooms/AB3KP9/socket" \
  -H "Authorization: Bearer $TOKEN"
```

Rooms are in-memory; restarts wipe them. The `min_machines_running = 0`
auto-stop in dev means an idle server WILL kill any open lobby
sessions on cold-stop. Acceptable for dev; production fly.toml should
pin `min_machines_running = 1` (already noted in DEPLOY footer below).

**Seat sweep cadence matters.** Disconnects are common on mobile;
the in-process reaper handles the live case, but the `sweep-rooms` cron
above is the backstop for seats + rooms stranded by a restart. Make sure
it's scheduled (and `STALE_ROOM_TTL_HOURS` set) before inviting real
users.

## Server build slimming (`cards.serverOnly`)

The Docker image only carries what `:apps:server` needs — the server
module itself plus the three shared KMP libraries it depends on
(`:libraries:core`, `:libraries:gameplay`, `:libraries:bots`). The rest
of the repo (features, client-only libraries, the Compose/iOS apps) is
omitted from the build context, and the Android SDK / Kotlin Native
toolchain are not installed in the image.

Three pieces conspire to make this work — keep them in sync:

1. **`settings.gradle.kts`** reads `-Dcards.serverOnly=true` and only
   `include(...)`s the server + the three shared libraries. Without this,
   Gradle 9 fails configuration with "project directory does not exist"
   for every missing `:features:*` module.
2. **`build-logic/.../KotlinMultiplatform.kt` + `KotlinMultiplatformConventionPlugin.kt`**
   skip `androidTarget()`, the iOS targets, and the `com.android.library`
   plugin when `cards.serverOnly` is set. The per-library
   `jvm { jvmTarget = JVM_17 }` block still supplies the JVM target.
3. **`apps/server/Dockerfile` + `apps/server/.dockerignore`** pass
   `-Dcards.serverOnly=true`, `COPY` the three library directories, and
   un-ignore them with `!libraries/<name>/`.

**Adding a new `:libraries:*` dep to the server.** The Docker build will
fail at link time with `Project with path ':libraries:foo' could not be
found`. That's the deliberate flag to:

1. Add `include(":libraries:foo")` to `settings.gradle.kts`'s always-on
   section (above the `if (!serverOnly)` block).
2. Add `COPY libraries/foo/ libraries/foo/` to the Dockerfile.
3. Add `!libraries/foo/` to `.dockerignore`.

The new lib must be a KMP module with a `jvm()` target pinned to JVM 17
(see `libraries/core/build.gradle.kts` for the pattern and the
rationale on why the pin is scoped to `jvm()` and not the whole module).

## Day-to-day

- **Tail logs**: `fly logs -a cards-server-dev`
- **SSH into running VM**: `fly ssh console -a cards-server-dev`
- **List recent deploys**: `fly releases -a cards-server-dev`
- **Roll back**: `fly releases rollback <version> -a cards-server-dev`
- **Rotate a secret**: `fly secrets set KEY=value -a cards-server-dev` (triggers redeploy)

## Cost

- VM: shared-cpu-1x with 256MB RAM. Free tier covers it.
- Idle: scales to zero, so cold cost is ~$0 in dev.
- Bandwidth: free tier covers ~100GB/mo egress; we're not close.
- Realistic dev monthly bill: **$0** while we're on free tier.

## When `cards-server` (prod) ships

Copy `fly.toml` to `apps/server/fly.prod.toml`, edit:
- `app = 'cards-server'`
- `min_machines_running = 1` (no cold-start stalls for real users)
- Possibly `[[vm]] memory = '512mb'` if we see GC pressure

Provision with `fly apps create cards-server` + `fly secrets set ... -a cards-server` (pointing at the **prod** Supabase project's `DATABASE_URL` and `SUPABASE_JWT_SECRET`). Then `fly deploy --config apps/server/fly.prod.toml --remote-only`.
