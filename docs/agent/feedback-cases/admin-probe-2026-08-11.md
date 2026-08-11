# admin-probe-2026-08-11 — unauthenticated third party enumerated the `/v1/admin` money endpoints on prod

**Signal:** Grafana / Loki, not Sentry. The prod server log
(`{service_name="cards-server", deployment_environment="prod"}`) over 2026-07-13 → 08-11 contains
exactly three unauthenticated `/v1/admin` hits from outside the owner's config console:

- `405 Method Not Allowed: GET - /v1/admin/messages` (2026-08-08)
- `405 Method Not Allowed: GET - /v1/admin/grant-chips` (2026-08-08, ~1s later)
- `401 Unauthorized: GET - /v1/admin/config/manifest` (2026-07-31)

Interleaved with `404 GET /robots.txt` hits, so the caller is a crawler/scanner, not a stray
client. Everything else on `/v1/admin/*` in the window is the owner's own console
(`PUT /v1/admin/config/manifest` etc.) with a valid token.

**Not a breach.** Every `/v1/admin` route is gated by `authenticatedAsAdmin` (`X-Admin-Token`,
constant-time compare) — verified at `AdminRoutes.kt` lines 56/89/127/153/192/229/343. The two
405s are Ktor's method-mismatch response, which fires before the handler, so the scanner learned
only that the path exists and wants POST. `A1 · Ledger conservation drift` is normal, so no
chips moved. The endpoint names are in the public repo anyway.

**The actual gap (worth fixing):** a failed admin-token attempt is *invisible*.

1. **No telemetry.** The 401 branch just `respond`s — nothing logged at warn/error, no metric, no
   event. A sustained brute force against `POST /v1/admin/grant-chips` would look identical to
   silence on every dashboard, and no alert covers it (A1 only catches chips that already moved,
   i.e. after the fact).
2. **No dedicated rate limit.** `/v1/admin` opts into no `RateLimitName` bucket, so the only
   ceiling is the global 600 req/IP/min in `RateLimits.kt` — generous for a token-guessing loop,
   and per-IP, so it's trivially spread.

Low urgency (high-entropy token, no evidence of an attempt beyond two probes), but this is the
one route family that mints chips and edits live config, and it's the surface with the least
observability on the server.

**Fix direction:** log the failed-admin-auth branch once at WARN with path + `clientIp()` (reuse
the existing helper) so it lands in Loki as a filterable line; register an `ADMIN_AUTH_LIMIT`
bucket (tight, e.g. 20/hour/IP) and wrap the `route("/v1/admin")` block in it. Optionally add an
A8 rule on the WARN line so a real brute force pages instead of being reconstructed afterwards.
Don't log the presented token.

**Disposition:** todo ENG-41 `[P2]` (2026-08-11). No Sentry issue exists for this signal — it was
found in the server log sweep.
