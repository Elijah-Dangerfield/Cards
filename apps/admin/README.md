# Remote config admin (local web GUI)

The on-demand admin tool for remote config / feature flags. It's a Compose
Multiplatform **web** app (Compose HTML / DOM) that you run locally, edit flags
with, and kill when you're done. Nothing is deployed, and no token is stored
anywhere.

It's the only `js` target in the repo and shares no code with the client. It
talks to the server's token-gated `/v1/admin/config` API over HTTP.

## Run it

```bash
# hot-reloading dev server (recommended)
./gradlew :apps:admin:jsBrowserDevelopmentRun --continuous

# or a one-shot run
./gradlew :apps:admin:jsBrowserRun
```

Gradle prints the localhost URL it's serving on. In the page, fill in three
fields and click the Connect / Reload button:

1. **Server URL.** Defaults to `http://localhost:8080` (a local `:apps:server`).
   Point it at any environment you hold the admin token for. Dev is
   `https://cards-server-dev.fly.dev`; prod is `https://cards-server-prod.fly.dev`.
   Each environment has its own admin token and its own flags (separate
   databases), so a change here only affects the one you're pointed at.
2. **Admin token.** The server's `ADMIN_API_TOKEN`, sent as the `X-Admin-Token`
   header. It's never persisted. See "Getting a token" below.
3. **Actor.** Your name. It's recorded on every change in the audit log.

## Getting a token

The token is just the target server's `ADMIN_API_TOKEN` env var. There's no
central authority and no registration: you mint a random string and set it on
whichever server you want to manage. The two cases are separate.

**A local server.** Add a token to `apps/server/.env`, then restart the server.
The admin API fails closed, so without this it returns 401.

```bash
echo "ADMIN_API_TOKEN=$(openssl rand -hex 32)" >> apps/server/.env
```

Point the tool's Server URL at `http://localhost:8080` and paste that same value.

**A deployed server (Fly).** The dev server already has a token, but Fly secrets
are write-only so you can't read the current one back. Rotate to a fresh value
you'll know, keeping the Fly secret and the GitHub secret (the cron sweeps use
it) in sync. From the repo root:

```bash
NEW_TOKEN="$(openssl rand -hex 32)"
fly secrets set ADMIN_API_TOKEN="$NEW_TOKEN" -a cards-server-dev
gh secret set CARDS_ADMIN_API_TOKEN_DEV --body "$NEW_TOKEN"
echo "$NEW_TOKEN"   # paste into the tool, then clear your scrollback
```

`fly secrets set` triggers a redeploy. Don't rotate a server's token while
something else (the sweeps) is authenticating against it without updating both
places. Full rotation notes live in [`apps/server/DEPLOY.md`](../server/DEPLOY.md).

## What you can do

- **Flags.** Edit a flag's base value (any JSON: `false`, `6`, `"off"`), add a
  new flag by dotted path, or delete one.
- **Rules.** Per flag, add ordered targeting rules (platform, version-code
  range, country, locale, user-id allow/deny, staged rollout %), toggle them on
  or off, or delete them. First matching rule wins; otherwise the base value.
- **Audit log.** Every change, newest first, with before/after.

Edits go live on the client's next config refresh (the server caches the
resolved tree for about 30 seconds).

## Why this exists / scope

We decided on an in-house tool over a hosted flag service; see
[`docs/post-launch.md`](../../docs/post-launch.md). The tool lists the flags
that exist **in the database**, plus a free-form "add a flag by path" field. It
does not enumerate the client's full `ConfiguredValue` registry, since that's
client common code this web target deliberately doesn't depend on. Publishing it
behind a VPN is a possible future step; for now it's local-only.
