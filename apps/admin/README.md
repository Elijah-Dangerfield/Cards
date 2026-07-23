# Remote config admin console

The admin console for remote config / feature flags. It's a Compose
Multiplatform **web** app (Compose HTML / DOM), **hosted by each environment's
server at `/admin`**:

- dev: `https://cards-server-dev.fly.dev/admin/`
- prod: `https://cards-server-prod.fly.dev/admin/`

It's the only `js` target in the repo and shares no code with the client. It
talks to the server's token-gated `/v1/admin/config` API over HTTP.

## How it works (the 60-second mental model)

- **Each server serves its own console.** The deploy workflows build the JS
  bundle and ship it inside the server image (`apps/server/admin-web/` →
  `/app/admin-web`, served by `installAdminWeb`). The console at a given origin
  manages that origin's server + database; the sibling environment is a link to
  its own console.
- **Tokens are pasted at runtime, never baked in.** This repo is public — the
  bundle holds no secrets. On first use you paste the env's `ADMIN_API_TOKEN`;
  the console validates it with a real call, then keeps it in that browser's
  localStorage (per env, with a "Forget token" button). Every request carries
  `X-Admin-Token`, plus `X-Admin-Actor: <your name>` for the audit log.
- **The page is public but inert.** Serving the HTML/JS reveals nothing that
  isn't already in this public repo; every API call it can make is token-gated.
- **Cross-origin still matters for local dev.** When you run the console
  locally against a deployed server, the browser preflights with the admin
  headers and PUT/DELETE methods — both must be in the server's CORS allow-list
  (`apps/server/.../plugins/Cors.kt`).

```
 your browser  ──HTTP + X-Admin-Token──▶  Cards server (Fly or local)  ──▶  Postgres
   /admin bundle                           /v1/admin/config …                app_config_* tables
```

## Quickstart

**Normal use:** open the environment's `/admin/` URL, paste that env's
`ADMIN_API_TOKEN` (Fly secrets are write-only, so the token's source of truth
is wherever it was generated — ask, or rotate it), set **Actor** to your name,
Connect. Done — the token is remembered by this browser until you forget it.

**Working on the console itself:** the **"Admin Web"** run config in the IDE, or:

```bash
# hot-reloading dev server (recommended — rebuilds on save)
./gradlew :apps:admin:jsBrowserDevelopmentRun --continuous
```

Gradle prints the `localhost` URL. The local run isn't served by a known env,
so you get all three env buttons (Local / Dev / Prod) and paste tokens the same
way.

## Using the tool

- **Target lens.** Set a synthetic client — platform, app version, country,
  locale, user id, install id — and hit *Resolve*. The flag table then shows,
  per flag: **in-code default → DB base → which rule won → resolved value** for
  that target. This is how you answer "what does a 9.1 / US user actually get."
- **Flags.** Each flag expands to a detail view: edit the base value, see its
  rules as plain sentences, and add/edit/enable/disable/delete rules. Add a
  brand-new flag by dotted path at the bottom.
- **Rules / targeting.** Per flag, ordered rules (first match wins, else base):
  platform, **semantic app-version bounds** (`> 1.0.1`), build-code range,
  country, locale, user-id allow/deny, staged rollout %. "Add rule for this
  target" pre-fills the conditions from the lens above.
- **Versions.** What a captured build shipped with — the in-code defaults per
  app version (see the manifest section below).
- **Audit.** Every change, newest first, with before/after diffs.
- **Kill switches.** The pinned panel above the tabs holds the emergency flags
  (maintenance mode/message, min supported build) with an ALL CLEAR / BANNER /
  BLOCKING state header.
- **Guardrails.** Every write shows a before → after confirm sheet naming the
  environment. Writes are also type-checked server-side against the registry
  (you can't set `social.enabled = 6`); lockout/force-upgrade changes get
  scarier wording, and on prod they require typing the env name. Failed writes
  stay in a dismissible error log with the attempted value.

Edits go live on the client's next config refresh (the server caches the
resolved tree for ~30 seconds).

## Testing it end to end

The page is just a client — it needs a **server that has these endpoints and the
CORS allow-list**. Two ways to get one:

### A) Against a deployed environment (dev/prod)

Pick **Dev** (or Prod) and Connect. This only works once the deployed server
includes the config-admin endpoints **and** the CORS header allow-list. If you
get **403**, the server is rejecting the admin headers at CORS (it predates the
fix); if flags load but the target lens / Versions are empty with an "unavailable
on this server" note, the server predates the manifest/resolve endpoints. The fix
is to deploy a server build that includes them (dev auto-deploys on push to
`main`; see `server-deploy.yml`).

### B) Locally, end to end (no deploy needed)

Run the server on your machine and point the page at it with the **Local** env.

```bash
# 1. a throwaway Postgres
docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=cards postgres:16

# 2. the server — Flyway applies every migration (incl. the config tables) on boot.
#    Put these in apps/server/.env (gitignored) or export them, then run:
#      DATABASE_URL=postgresql://postgres:cards@localhost:5432/postgres
#      SUPABASE_URL=https://yuqrfhdoejonclgbixlw.supabase.co   # any valid URL; admin routes don't use JWT
#      ADMIN_API_TOKEN=<same value as `local=` in admin-tokens.local.properties>
./gradlew :apps:server:run

# 3. the page (separate terminal), then pick the "Local" env and Connect
./gradlew :apps:admin:jsBrowserDevelopmentRun --continuous
```

Local Postgres starts empty, so there are no flags until you add one (or upload a
manifest — see below). This is the fastest way to exercise the full feature set,
including the per-target resolve and the Versions tab, without touching a shared
database.

## Previews?

Compose **HTML** (what this module uses) has **no `@Preview`** — that's a
Compose-UI / Android feature, and this isn't Compose UI, it's DOM. The preview
*is* the hot-reloading dev server: run `jsBrowserDevelopmentRun --continuous` and
the browser updates on save. For a quick render check without a live server you
can also serve the built bundle statically
(`apps/admin/build/dist/js/developmentExecutable`), but the dev server is the
normal loop.

## Per-version defaults manifest (what a build shipped with)

The admin tool can show the **in-code defaults a given app version shipped
with** — the baseline a remote override replaces. Those defaults live in the
client's `ConfiguredValue` classes, which the JS admin module can't read, so the
build exports them and uploads them per deploy.

**This is automated.** The `Upload config manifest` step in the server-deploy
workflows ([`server-deploy.yml`](../../.github/workflows/server-deploy.yml) for
dev, [`server-deploy-prod.yml`](../../.github/workflows/server-deploy-prod.yml)
for prod) runs `exportConfigManifest` and PUTs the result after each deploy,
using the `CARDS_ADMIN_API_TOKEN_{DEV,PROD}` secret. It's stamped from
`versions.properties` and idempotent (re-uploading a version replaces its rows),
and it skips without failing the deploy if the token secret isn't set. Both
`CARDS_ADMIN_API_TOKEN_DEV` and `CARDS_ADMIN_API_TOKEN_PROD` exist, so dev and
prod both upload on deploy.

To do it by hand (e.g. to backfill a version, or to seed a local server):

```bash
./gradlew :apps:admin:exportConfigManifest
# writes apps/admin/build/config-manifest.json, stamped from versions.properties

curl -X PUT "$SERVER_BASE_URL/v1/admin/config/manifest" \
  -H "X-Admin-Token: $ADMIN_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data @apps/admin/build/config-manifest.json
```

### Keeping the registry honest

The registry is a committed, reviewable file: `apps/admin/config-manifest-registry.json`
(the live `ConfiguredValue` multibinding is Android/iOS-only, so neither this JS
module nor a JVM build task can read it directly). Two guards keep it from drifting:

- `exportConfigManifest` **structurally validates** it (valid types, unique paths,
  each default matches its declared type, enum defaults ∈ allowed values) and fails
  the build on any inconsistency.
- `ConfigManifestDriftTest` (in `:apps:integration`, runs in CI) instantiates the
  **real** scalar `ConfiguredValue` classes and fails if the registry's path / type
  / default / allowed-values have drifted from the code.

So: when you add, remove, or change a scalar `ConfiguredValue`, update
`config-manifest-registry.json` — the drift test will tell you if you forget.
Composite (`JsonConfigValue`) flags are intentionally omitted.

## Why this exists / scope

We decided on an in-house tool over a hosted flag service; see
[`docs/post-launch.md`](../../docs/post-launch.md). Auth is a single shared
admin token per environment (no per-user login/roles yet) — SSO/RBAC is a
possible future step. localStorage tokens are readable by JS on the console's
origin; acceptable here because the page loads no third-party scripts and
Compose HTML escapes all text nodes.
