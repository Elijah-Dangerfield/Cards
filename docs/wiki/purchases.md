# How chip purchases work

How a user buys chips, why the identity model is the way it is, and how we recover a purchase that gets stuck instead of leaving anyone out of pocket or blocked. For the wallet the chips land in, see `wiki/wallet.md`. For account identity, see `wiki/account-lifecycle.md`.

## The happy path

1. The user taps a chip pack in the shop. On iOS this runs Apple's StoreKit purchase sheet; on Android it runs Google Play Billing.
2. The store returns a signed receipt. The client sends it to `POST /v1/billing/redeem`.
3. The server validates it (`AppStoreReceiptValidator` on iOS): it checks Apple's signature, that the product matches the pack, that the receipt is bound to this account, and that it isn't revoked. Then it grants the chips.
4. Grants are idempotent, keyed on the transaction id, so the same receipt can only ever add chips **once**.
5. The client marks the transaction **finished**. That tells the store we're done with it.

That last step matters more than it looks, and it's the root of everything below.

## Only signed-in users can buy

When an anonymous user tries to buy, we stop them and ask them to sign in first. So the buyer is always a real claimed account (email, Apple, or Google), and we stamp the purchase with that account's id (the `appAccountToken`).

**Why this is the whole ballgame:** a claimed account id is stable across a reinstall. Delete the app, reinstall, sign back in with the same provider, and you're the same account id. So the id we stamped on the receipt still matches you later. That's why we don't need a device-survives-reinstall trick like an iCloud Keychain key. The account id already survives, as long as the user signs back in.

The catch is that a user can be signed in when they buy, then reinstall and browse anonymously *before* signing back in. In that window they're a fresh anonymous account, and an old receipt won't match them. That's the main way a purchase gets "stuck," and the recovery model below is built around it.

## Why purchases replay (and get stuck)

Chip packs are **consumables**. Apple's rule for consumables: the transaction stays "unfinished" until the app calls finish, and Apple keeps handing it back to the app on every launch until then. It's a safety net, so a purchase survives a crash between payment and delivery.

The failure mode is the flip side of that net. If we can't grant a transaction, we don't finish it, so Apple replays it forever. It re-presents on the next launch and trips the next purchase attempt. The user sees "I can't buy anything" even though the real problem is one old transaction we never resolved.

**The rule that prevents this:** once we've made a *terminal* decision about a transaction, we always finish it. The only transactions we leave open are ones we genuinely intend to retry. Everything else gets finished so it stops blocking the user.

## What "can't redeem" actually means (classify, then act)

"Can't redeem" is really four different situations. The server already tells us which one it is, and each wants a different response. The guiding principle: **never block the user, never silently eat their money, and always tell them what happened so they aren't left guessing.**

| Situation | What it is | What we do | Finish it? | What we tell the user |
| --- | --- | --- | --- | --- |
| **Transient** | Server down, network drop, validator not configured yet | Leave it, retry next launch | No, it retries | Nothing loud. A quiet "syncing your purchase" at most |
| **Account mismatch** | Valid, paid, signed receipt, just tied to a different one of the user's accounts | Nudge "sign in to claim," else grant to the current user | Yes, after granting | "We added your N chips" |
| **Dead** | Apple says revoked or refunded, or the receipt is malformed / wrong product | Finish it, grant nothing | Yes | "This purchase was refunded," when relevant |
| **Wedged** | Fails past a retry cap for a reason we didn't anticipate | Finish to unblock, flag for review, grant goodwill chips | Yes | "We hit a snag with a purchase from [date]. We're on it." |

The transient case is already handled today by `OutstandingPurchaseRedeemer`, which drains unfinished transactions on launch. The other three are the recovery work we're adding.

## Why we grant on a mismatch instead of refunding

For a receipt that Apple signed, that was paid, and that isn't revoked, we grant the chips to whoever is on the device now and finish it. This sounds risky and isn't, for four stacked reasons:

- **It can only grant once.** Grants are keyed on the transaction id, so a receipt adds chips one time, full stop. Relaxing the account check doesn't create free chips. It only decides *which* of the user's own accounts gets the one grant.
- **Replays only reach the same Apple ID.** Apple re-presents an unfinished transaction only to the Apple ID that bought it. So the "different account" is almost always the same human whose in-app account id changed on reinstall. Worst realistic case is a family sharing one Apple ID, where the grant lands on a sibling profile.
- **Chips cost us nothing to mint.** They're freemium virtual currency with no cash-out, so granting is the cheapest resolution and it's exactly what the user paid for.
- **We can't refund anyway.** Apple owns refunds (see below), so the alternative to granting is making a paying user chase Apple for their money. That's the worst outcome for trust.

**Guardrails, so this survives a security review** (a prior decision, BILL-11, deliberately kept the strict account binding and earmarked relaxing it for the real-money review):

- Prefer to relax only for a client-flagged **StoreKit replay** (the outstanding-drain path sets `replayed=true`), so the normal interactive buy flow never enters the relaxation path. **Be honest about what this enforces:** the `replayed` flag is client-asserted, and the server cannot cryptographically tell a genuine replay from a fresh submission of the same signed JWS. So the true security boundary is not "replay-only." It is **possession of a genuine, unredeemed, signed receipt**. An attacker who captures a victim's real receipt (jailbreak, shared Apple ID / Family Sharing, TLS intercept) can redirect that *one already-paid* purchase to their own account. This is account-integrity harm to the victim (they paid, the attacker got the chips), never a chip mint (idempotency still caps it at one grant per transaction id, ever) and never cashable out. For freemium chips that trade-off is acceptable; it is not "unforgeable."
- Rate-limit relaxed grants and log every one with the distinct `.replay` wallet reason, so anything abnormal is visible. (Caveat: the install-lineage widening below can promote a mismatched token to a plain `Valid` grant that skips this path, per the note.)
- Try the cheap recovery first: if the current user is anonymous, nudge "sign in to claim your purchase." Re-login makes the receipt match cleanly. Grant-and-finish is the fallback when they can't or won't.

**Install-lineage caveat.** The redeem route also widens the accepted account set to the caller's install lineage (`ProfileRepository.findInstallLineage`, BILL-11) so a pack bought before an account upgrade still redeems as a clean `Valid` grant. That lineage is derived from the `install_id` the client sends in the `X-Install-Id` header, which is a correlation id, not a secret. So a caller who learns a victim's `install_id` can widen their lineage to include the victim and have the victim's receipt validate as a plain `Valid` grant, bypassing the rate limit, the `.replay` reason, and the audit trail. Same bound as above (still needs the victim's genuine token, still one grant), but it is the stealthier path. Tightening this (server-established linkage instead of the client header, or a per-`install_id` account cap) is a tracked follow-up in `backlog.md`.

## We can't issue App Store refunds

Worth stating plainly, because it removes an option people assume we have: an app cannot refund an App Store purchase. Apple owns refunds. The user requests one from Apple, or Apple grants one and notifies us. Our real levers are: grant the chips ourselves, hand out goodwill chips, or point the user at Apple's refund flow. For a genuinely wedged case we lean toward goodwill chips over the Apple refund maze, because it's cheaper for us and better for them.

## What the user can see

- **A purchase history screen in the app.** Users technically can find purchases in the App Store (Settings, Media and Purchases, Purchase History), but it's buried and not app-specific, so it does nothing for confidence in *our* app. We already hold the grant records, so we show a real list: each pack, the date, and its status (added, pending, refunded).
- **A "sync purchases" button** on that screen that re-runs the outstanding-transaction drain on demand. Consumables have no classic "restore," so this is the equivalent, and a button that says "check for missing purchases" is reassuring.
- **A message when a stuck one resolves.** If a pending purchase grants later (they signed in, or a retry finally worked), tell them: "We found a purchase and added 500 chips." An in-app message on next open is enough. Every outcome should reach the user, so a delayed grant reads as intentional, not random.

## What we can see (telemetry and a persisted record)

Persist **every** redeem attempt and its disposition, not just the successful grants. A small billing-events record per attempt: transaction id, the receipt's owner account, the current caller account, product, failure reason, attempt count, the final action (granted, granted-on-replay, finished-dead, pending, escalated), and timestamps.

That record is the source of truth for two things: support ("what happened to this person's purchase") and a Grafana billing-health panel: pending and stuck counts with age, mismatch rate, grant-on-replay rate, revoked and refunded counts, escalations, and retry distributions, sliceable by reason. A spike in mismatches means a real regression, so the panel doubles as an early warning, not just a support tool.

## The principles, in one place

- Never leave the user blocked. A terminal transaction always gets finished so it can't re-present.
- Never silently eat their money. If we can't grant, we tell them and make it right.
- Always close the loop with a message. No silent successes, no silent failures.
- Prefer granting chips over refunds we can't issue. The receipt is real and chips are free to mint.
- Keep the money path observable. Every attempt is recorded and visible in Grafana.

## What exists today vs. what's next

**Already in place:** the buy flow and `POST /v1/billing/redeem`, `AppStoreReceiptValidator` (signature, product, account binding, revocation, transaction-id idempotency), the install-lineage widening for same-install account upgrades (BILL-11), and `OutstandingPurchaseRedeemer` draining unfinished transactions on launch (the transient case).

**To build:** the classifier and per-case responses, grant-on-replay with the guardrails above, the persisted billing-events record and its migration, the Grafana billing-health panel, the in-app purchase-history screen with the sync button, and the user-facing message for each outcome. This is money-path code, so it wants tests and a real device plus a StoreKit sandbox pass before it's trusted.
