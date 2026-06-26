# Remote config admin (local web GUI)

The on-demand admin tool for remote config / feature flags. A Compose
Multiplatform **web** app (Compose HTML / DOM) you run locally, edit flags with,
and kill when you're done. Nothing is deployed; no token is stored anywhere.

It's the only `js` target in the repo and shares no code with the client — it
talks to the server's token-gated `/v1/admin/config` API over HTTP.

## Run it

```bash
# hot-reloading dev server (recommended)
./gradlew :apps:admin:jsBrowserDevelopmentRun --continuous

# or a one-shot run
./gradlew :apps:admin:jsBrowserRun
```

Gradle prints the localhost URL it's serving on. In the page:

1. **Server URL** — defaults to `http://localhost:8080` (a local `:apps:server`).
   Point it at any environment you have the admin token for.
2. **Admin token** — the server's `ADMIN_API_TOKEN`. Sent as `X-Admin-Token`,
   never persisted.
3. **Actor** — your name; recorded on every change in the audit log.

Then **Connect / Reload** to load the flags.

## What you can do

- **Flags** — edit a flag's base value (any JSON: `false`, `6`, `"off"`), add a
  new flag by dotted path, or delete one.
- **Rules** — per flag, add ordered targeting rules (platform, version-code
  range, country, locale, user-id allow/deny, staged rollout %), toggle them on/
  off, or delete them. First matching rule wins; otherwise the base value.
- **Audit log** — every change, newest first, with before/after.

Edits go live on the client's next config refresh (the server caches the
resolved tree for ~30s).

## Why this exists / scope

Decided in-house over a hosted flag service; see
[`docs/post-launch.md`](../../docs/post-launch.md). The tool lists the flags
that exist **in the database**, plus free-form "add a flag by path" — it does
not enumerate the client's full `ConfiguredValue` registry (that's client common
code this web target intentionally doesn't depend on). Publishing it behind a
VPN is a possible future step; for now it's local-only.
