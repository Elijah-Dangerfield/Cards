# Post-launch

Committed work we intend to do, but **not** before the V1 launch. Distinct from
[`backlog.md`](./backlog.md) (someday/maybe ideas we may never do) and from
[`todo.md`](./todo.md) (the launch punch list). When launch is behind us, items graduate
from here into `todo.md`.

Each item carries enough context to pick up cold. Append; delete when graduated or dropped.

---

## Anti-abuse

### App attestation (Play Integrity / App Attest)
Decided **no for V1** (2026-06-19). Apple App Attest + Google Play Integrity let the server
confirm a request comes from a genuine, untampered build on a real device — real anti-fraud
value on sensitive endpoints (purchase verification, wallet sync, achievement grants). Deferred
because chips aren't cash-out-able, so the cheating payoff is low, and attestation adds setup cost
plus a small legitimate-user failure rate (rooted devices, attestation outages). **Revisit if
backend abuse from forged clients materializes.** If adopted: a server gate on the sensitive routes
+ per-platform client integration.

### Automated ban sweep
Manual banning is the V1 model (triggers + enforcement live in `developer-todo.md` / `todo.md`).
Post-launch, add a **weekly sweep** that flags obvious bad actors above a confidence threshold
(e.g. clear chip-dumping / collusion patterns) and auto-bans the unambiguous ones; everything below
the bar surfaces for manual review rather than auto-acting. Pairs with the reporting feature below.

### In-app reporting + report-threshold auto-ban
Let players report another player (abusive name / chat / emotes, suspected collusion). Post-launch,
once reporting exists, add a rule: **≥ 3 reports against one account within 72 hours → auto-ban**
(reviewable / reversible via the same appeal email as manual bans). Feeds the sweep above.

## Accounts

### Auto-trigger the inactivity-based orphan sweep
Opportunistic orphan deletion is shipped (`DefaultOrphanInstallSweep`, fires on `/v1/me` when a
device re-binds to a different active anon, with the no-purchase / no-meaningful-XP guards from
`decisions.md` 2026-06-19). The ≥-1-year inactivity sweep is also built (`DefaultOrphanAnonymousSweep`,
exposed at `POST /v1/admin/sweep-anonymous-users`) — but it only runs when something hits the route.
Post-launch, wire an automatic trigger (Fly scheduled task, cron, GitHub Actions cron) so the sweep
runs without a manual kick. Low priority: orphan rows are cheap, and the conservative-by-design
guards (no purchases, no high XP, no active room seat) mean a missed sweep just leaks rows, never
deletes someone's progress.

## Social / virality

### Friend-game link previews (Universal Links + App Links + web host)
Rich iMessage/WhatsApp previews showing a Downcard-branded card with stakes + seat count when a
friend-game link is shared (was product-spec §5.2, since deleted). **Deferred, not a blocker** —
friend games work today via copy-code; this is virality polish that only pays off once there are
users sharing links.

Three external pieces gate the engineering work:
- **iOS Universal Links** — enable the Associated Domains entitlement on the App ID, host the AASA
  file at `https://downcard.app/.well-known/apple-app-site-association` (served as `application/json`,
  no redirect). Needs the Apple **Team ID** + bundle id.
- **Android App Links** — host `assetlinks.json` at `https://downcard.app/.well-known/assetlinks.json`.
  Needs the Play App Signing **SHA-256 fingerprint** + package name.
- **Preview endpoint** — a *dynamic* route (e.g. `downcard.app/j/{code}`) serving Open Graph meta
  (`og:title`, `og:image`, `og:description`) keyed by the friend-code. GitHub Pages can't do this; it
  needs the Fly server or a serverless function on the same domain.

Then engineering picks up: the Associated Domains plist entry + Android intent-filters, the deep-link
handler routing `/j/{code}` into the join flow, and the OG-image renderer.

**Why it waited:** the AASA Team ID and the Android signing fingerprint don't exist until **Downcard
App LLC + the Apple/Google developer accounts are finalized** (see `developer-todo.md`), so any prep
would go stale. Revisit once those exist and there's real friend-game traffic to justify the renderer.

*(The remote-config / feature-flag system that used to be tracked here shipped 2026-06-26 and
graduated to [`wiki/remote-config.md`](./wiki/remote-config.md).)*
