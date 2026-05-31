# Chip grants

How chips enter a player's wallet, and how the app reveals them. For the
product rationale (single currency, "chips feel sacred," why we're generous
instead of scarce) see [product-spec §4.1](../product/product-spec.md#41-chips--the-only-currency).
This page is the "how it works" companion.

## The one rule

**The server is the only source of truth for chip balances.** The client
never invents a chip number — not the starting amount, not a grant. The
client wallet is a local cache that the server reconciles on every sync, and
the server always wins. So anything the UI *shows* is a number the server
handed us (or, when offline, a promise we know the server will honor).

## The three faucets

All three are awarded server-side and recorded as append-only rows in a
`wallet_events` ledger. Each row has a stable idempotency key, so a grant can
never be applied twice no matter how many times the client retries.

| Grant | Amount | When it fires | How often |
|-------|--------|---------------|-----------|
| **Starter grant** | 10,000 | The first time the wallet is touched after signup (it's created on demand). | Once per wallet. |
| **Welcome-week** | 500 / day | Each of the 7 days *after* signup day, applied the first time the wallet is contacted that day. Signup day itself gets only the starter grant. | Once per (user, day). Missed days are granted on the next open — no streak, no expiry. |
| **Bust protection** | 1,000 | When the balance hits 0 ("Welcome back to the table"). | Lifetime-once per user. |

A player who opens the app every day their first week ends week one with
10,000 + (7 × 500) = **13,500** chips.

> Chips never expire and are never silently taken. The only way chips leave a
> wallet is the player voluntarily spending them in the shop or losing them at
> a table. No timed bonuses, no "come back" nudges. (See product-spec §4.1.)

## How it actually happens

1. **Lazy creation.** A brand-new account has no wallet row. The first wallet
   contact — either loading the balance or the first sync — creates the row
   and seeds it with the starter grant. This is idempotent: the second call
   just reads the existing row.
2. **Recurring grants ride along.** Welcome-week and bust-protection are
   re-checked on *every* wallet contact and applied if the player is eligible.
   That's why the daily +500 lands with no dialog — it's just there the next
   time the wallet syncs.
3. **The client is optimistic, the server reconciles.** Locally the app keeps
   a balance and a small outbox of pending changes (e.g. a shop purchase it
   applied immediately). On sync it sends the outbox and the server returns
   the authoritative balance, which overwrites the local one. Sync runs
   automatically on cold boot and when the app comes to the foreground.

## Showing the starter grant (the reveal)

We want new players to *see* their starting chips, but we never want to show a
number we haven't actually confirmed with the server. The trick is to treat
the server's "I just created this wallet" signal as the trigger:

1. On the first wallet contact for a new account, the server flags the
   response `walletCreated = true`.
2. The client records "we still owe this player the grant reveal."
3. **Onboarding's final page** tries to show the real balance:
   - If the wallet has synced in time → it reveals the actual number and marks
     the reveal done.
   - If we're offline / slow → it shows "your starter chips land the moment
     you're back online" (no number) and leaves the reveal pending.
4. **The Home welcome dialog** is the fallback. It fires only if the reveal is
   still pending *and* a real balance has arrived — so offline players, or
   players who skipped onboarding (e.g. signed in with Apple/Google straight
   to Home), still get the reveal exactly once, with the true number.

Because the trigger is "the server created the wallet," a **returning player
who reinstalls** does **not** see the starter reveal again — their wallet
already exists, so `walletCreated` is false. (This previously misfired; it's
now fixed.)

## What this deliberately avoids

- **No client-side starter constant.** If the client hard-coded "10,000," it
  could disagree with the server. Instead the displayed number is always the
  server's, or an honest "coming when you reconnect."
- **No reconciliation of a shown number.** We never claim a balance we'd later
  have to walk back, so there's nothing to reconcile beyond the wallet's normal
  sync.

## Related

- Product rationale & economy design: [product-spec §4 Economy](../product/product-spec.md#4-economy)
- Reinstall / orphaned-account behavior: [recovery-and-orphaned-accounts.md](../recovery-and-orphaned-accounts.md)
- Real-money chip packs (IAP) are a separate faucet on the *human* side of the
  economy — see product-spec §4.4.
