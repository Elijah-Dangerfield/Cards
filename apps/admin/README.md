# Remote config admin (local web GUI)

The on-demand admin tool for remote config / feature flags. It's a Compose
Multiplatform **web** app (Compose HTML / DOM) that you run locally, edit flags
with, and kill when you're done. Nothing is deployed; the admin tokens live only
in a gitignored local file, baked into the local-only bundle at build time.

It's the only `js` target in the repo and shares no code with the client. It
talks to the server's token-gated `/v1/admin/config` API over HTTP.

## One-time setup: the tokens file

The tool talks to two environments, **dev** (`cards-server-dev`) and **prod**
(`cards-server-prod`), each a separate database with its own admin token. The
tokens are **baked into the bundle at build time** from a gitignored file (the
React-`.env` equivalent), so you never paste a token in the UI.

Create `apps/admin/admin-tokens.local.properties` (gitignored) with the two
`ADMIN_API_TOKEN` values:

```properties
dev=<cards-server-dev ADMIN_API_TOKEN>
prod=<cards-server-prod ADMIN_API_TOKEN>
```

Fly secrets are write-only (you can't read a token back), so this local file is
your source of truth. To rotate one, set it on Fly + the matching GitHub secret
and update this file. See [`apps/server/DEPLOY.md`](../server/DEPLOY.md).

## Run it

Click the **"Admin Web"** run config in the IDE, or:

```bash
# hot-reloading dev server (recommended)
./gradlew :apps:admin:jsBrowserDevelopmentRun --continuous

# or a one-shot run
./gradlew :apps:admin:jsBrowserRun
```

Gradle prints the localhost URL it's serving on. In the page:

1. **Pick the environment** with the Dev / Prod buttons. The URL + baked-in token
   are selected for you. (A blank token means that key is missing from
   `admin-tokens.local.properties`; add it and rebuild.)
2. **Actor**: your name, recorded on every change in the audit log.
3. **Connect / Reload.**

A change only affects the environment you picked; dev and prod are separate
databases.

## What you can do

- **Flags.** Edit a flag's base value (any JSON: `false`, `6`, `"off"`), add a
  new flag by dotted path, or delete one.
- **Rules.** Per flag, add ordered targeting rules (platform, version-code
  range, country, locale, user-id allow/deny, staged rollout %), toggle them on
  or off, or delete them. First matching rule wins; otherwise the base value.
- **Audit log.** Every change, newest first, with before/after.

Edits go live on the client's next config refresh (the server caches the
resolved tree for about 30 seconds).

## Per-version defaults manifest (what a build shipped with)

The admin tool can show the **in-code defaults a given app version shipped
with** — the baseline a remote override replaces. Those defaults live in the
client's `ConfiguredValue` classes, which the JS admin module can't read, so CI
uploads them per release.

Generate the manifest for the current build and upload it:

```bash
./gradlew :apps:admin:exportConfigManifest
# writes apps/admin/build/config-manifest.json, stamped from versions.properties

curl -X PUT "$SERVER_BASE_URL/v1/admin/config/manifest" \
  -H "X-Admin-Token: $ADMIN_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data @apps/admin/build/config-manifest.json
```

Run this as a release-pipeline step (after the version bump) so each shipped
build records its registry.

> **Keep the registry in sync.** The exported entries are a maintained list in
> `apps/admin/build.gradle.kts` (`exportConfigManifest`). When you add, remove,
> or change a scalar `ConfiguredValue`'s default, update that list. Composite
> (`JsonConfigValue`) flags are intentionally omitted.

## Why this exists / scope

We decided on an in-house tool over a hosted flag service; see
[`docs/post-launch.md`](../../docs/post-launch.md). The tool lists the flags
that exist **in the database**, plus a free-form "add a flag by path" field. It
does not enumerate the client's full `ConfiguredValue` registry, since that's
client common code this web target deliberately doesn't depend on. Publishing it
behind a VPN is a possible future step; for now it's local-only.
