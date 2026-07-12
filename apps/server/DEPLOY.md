# Deploying the Cards server to Fly.io

The first deploy is a one-time setup. After that, merging to `main` with
changes under `apps/server/**` triggers an auto-deploy of **dev** via GitHub
Actions (see `.github/workflows/server-deploy.yml`).

## Environments (dev + prod)

There are two independent environments — separate Fly apps backed by separate
Supabase projects (separate databases), so config flags, wallets, and users in
one are invisible to the other.

| | Fly app | Supabase project | Config | Deploy |
| --- | --- | --- | --- | --- |
| **dev** | `cards-server-dev` | `yuqrfhdoejonclgbixlw` | `fly.toml` | auto on push to `main` |
| **prod** | `cards-server-prod` | `kzohlyvmnnvyabspzpbb` | `fly.prod.toml` | auto-queued on push to `main`, **1-click approval** (`server-deploy-prod.yml`) |

The client picks an environment by build type: **debug → dev, release → prod**
(`Environment.current`). Override locally with `cards.targetEnv=dev|prod` in
`local.properties` (CI-guarded). Each env has its **own** `ADMIN_API_TOKEN`, so
the config admin tool manages whichever one you point it at.

Prod deploys are deliberate but low-toil: dev ships on every merge; the same
merge **auto-queues** a prod deploy that pauses on the `production` GitHub
Environment for a one-click approval (**Actions → the run → "Review
deployments"**). Approve it once dev looks good. The `workflow_dispatch` path is
still there as a break-glass / redeploy button (type `prod` to confirm), as is
`fly deploy --config apps/server/fly.prod.toml` locally. The server is
deliberately **decoupled from the mobile app release** — see
[`docs/practices/release.md`](../../docs/practices/release.md) →
"Two release tracks". The schema needs no manual work — Flyway runs every
migration on first boot and `V5__products.sql` seeds the catalog, on either
database.

### Standing up prod (one-time)
```
fly apps create cards-server-prod
fly secrets set \
  DATABASE_URL='postgresql://postgres:<dev-password>@db.kzohlyvmnnvyabspzpbb.supabase.co:5432/postgres' \
  SUPABASE_URL='https://kzohlyvmnnvyabspzpbb.supabase.co' \
  SUPABASE_SERVICE_ROLE_KEY='<prod service_role key>' \
  ADMIN_API_TOKEN="$(openssl rand -hex 32)" \
  -a cards-server-prod
fly deploy --config apps/server/fly.prod.toml
fly tokens create deploy -a cards-server-prod --expiry 8760h   # → GH secret FLY_API_TOKEN_PROD
```
Set the prod project's DB password to match dev (so only the project ref
differs), set `CARDS_ADMIN_API_TOKEN_PROD` in GitHub to the prod
`ADMIN_API_TOKEN`, and put the prod anon key into `Environment.Prod` in
`libraries/core/.../Environment.kt`.

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
   (`cards-server-prod` is the prod counterpart — see "Standing up prod" above.)

4. **Set secrets** (from the repo root):
   ```
   fly secrets set \
     DATABASE_URL='postgresql://postgres:<URL-ENCODED-DB-PASSWORD>@db.yuqrfhdoejonclgbixlw.supabase.co:5432/postgres' \
     SUPABASE_URL='https://yuqrfhdoejonclgbixlw.supabase.co' \
     -a cards-server-dev
   ```
   Get `<URL-ENCODED-DB-PASSWORD>` from Supabase → Project Settings → Database.
   **Never commit the real value** — it's a secret; it only ever lives in `fly
   secrets` and the gitignored `apps/server/.env`.

   Notes:
   - `DATABASE_URL` uses Supabase's **direct connection** host (works from Fly because Fly has IPv6 outbound).
   - URL-encode special characters in the password (e.g. `$` → `%24`).
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
   For `cards-server-prod`, use `SENTRY_ENVIRONMENT='prod'` against the same Sentry project. Use one Sentry project for the server (separate from the client project) and let `SENTRY_ENVIRONMENT` differentiate dev vs prod issues — easier cross-env grouping than splitting projects.

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

(This workflow only sweeps dev today — a prod equivalent pointing at
`cards-server-prod.fly.dev` with `CARDS_ADMIN_API_TOKEN_PROD` hasn't
been added yet.) Response body shows `candidatesFound / deleted
/ failedToDelete`; a non-zero `failedToDelete` is worth investigating
in Sentry.

## Disconnected-room-member reaper (in-process)

Per-room WebSocket disconnect holds the member's seat in case the client
reconnects (mobile networks bounce all the time). The server schedules
a per-member reaper on every disconnect — `delay(5min)` then drop the
seat iff the member is still disconnected with the same stamp. A
reconnect (or a fresh disconnect) cancels the original reaper out
naturally because the stamp on the member no longer matches.

No cron, no admin token, no external scheduler. Rooms live in memory
on the same Fly instance as the socket, so an in-process timer is the
right tool — there's nothing for a separate process to coordinate.

If we ever move rooms to durable storage with a shared backplane, this
becomes "schedule a delayed task on the backplane" instead of an
in-process `launch { delay(...) }`. The grace constant lives in
[`RoomSocketRoutes.kt`](src/main/kotlin/com/cards/server/routes/RoomSocketRoutes.kt)
as `DEFAULT_REAPER_GRACE`.

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

Rooms are in-memory; restarts wipe them. Both fly configs pin
`min_machines_running = 1` (cold JVM boots were blowing past the client's
config-refresh timeout), so idle cold-stops aren't a factor anymore — but
any redeploy or crash still wipes live lobbies.

**Seat sweep cadence matters.** Disconnects are common on mobile;
without the sweep above wired up, abandoned seats block joiners. Make
sure the disconnect-sweep cron is scheduled before inviting real
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

- VM: shared-cpu-1x with 512MB RAM per environment (256MB OOM-looped the JVM — see the `JAVA_OPTS` note in `fly.toml`).
- One machine per environment stays warm 24/7 (`min_machines_running = 1`), so the baseline is a few dollars a month per app rather than $0-idle.
- Bandwidth: free tier covers ~100GB/mo egress; we're not close.
- Realistic monthly bill across dev + prod: **~$5-15**.
