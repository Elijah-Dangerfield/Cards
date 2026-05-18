# Shop / Inventory — Future Plans

Living document. Captures work the shop will need post-V1 but doesn't
yet have. Inline TODOs in code reference the section numbers here.

## 1. IAP catalog × platform-store SKU reconciliation

Today the server returns IAP chip packs identified by `StoreSku.sku`.
The client trusts the catalog and shows everything as buyable.

Real wiring needed:

- On app start (or shop screen open), fetch the **set of SKUs the
  platform store actually has available**:
  - iOS: StoreKit 2 `Product.products(for: skus)`
  - Android: `BillingClient.queryProductDetails`
- Cross-reference with the backend catalog:
  - Keep products where the platform store knows the SKU.
  - Drop or hide products where the SKU isn't found (server config is
    ahead of the store — should be rare in production, common in
    staging / sandbox / unrolled releases).
- Replace each `StoreSku.fallbackPriceDisplay` with the platform
  store's actual localized price string (`StoreProduct.displayPrice`
  on iOS, `pricingPhases` on Android). The fallback is for the brief
  window before the store call resolves.
- Telemetry events:
  - `shop.iap.empty_store_response` — store returned 0 SKUs (network
    failure? sandbox config gap? rollout block?)
  - `shop.iap.missing_skus` — server SKUs not in the store, count + ids
  - `shop.iap.price_resolved` — successful local price replacement,
    one per SKU
  - `shop.iap.purchase_started` / `.completed` / `.failed` / `.refunded`

## 2. Owned-inventory sync (last-write-wins server reconciliation)

Today: client writes inventory rows locally on chip-offer redeem; sync
service POSTs them to `/v1/inventory/sync` which pre-auth just echoes
back "Confirmed."

Real flow post-auth:

- Client redeems optimistically (already does this).
- Sync POSTs pending rows to the server along with the chip balance
  snapshot at redeem time.
- Server reconciliation per pending row:
  - Was the offer in-window at the time the client claims it
    redeemed? (Server has the authoritative offer history.)
  - Did the user actually have enough chips? (Server has the
    authoritative chip ledger.)
  - Is the offer still grantable? (Not revoked, not stale.)
- Outcomes the client handles:
  - **Confirmed** → drop the row's Pending flag.
  - **Reverted** → remove from local inventory, **re-credit chips**,
    surface a soft toast: "Your Marble Card Back purchase was
    refunded — insufficient chips at sync time." Used for: race with
    a concurrent purchase from another device, offer expired between
    local redeem and sync, item revoked for ToS, etc.
- Telemetry:
  - `shop.sync.confirmed` — per row
  - `shop.sync.reverted` with reason (`insufficient_chips`,
    `offer_expired`, `revoked`, `unknown`)
  - `shop.sync.failed` — network / server error, retry pending
  - `shop.sync.empty_inventory` — server returned no items for a user
    we believed had owned items (suggests account-tier or auth issue)

The last-write-wins rule applies on **chip BALANCE**, not on the
inventory row. We never silently mutate the user's owned-items list
without their knowledge; reverts always surface as a toast.

## 3. Profile / Your Items page

New screen at Profile > Your Items. Surfaces every owned cosmetic +
utility + emote pack.

Layout:
- Grid grouped by category (Felts, Card Backs, Emote Packs, Avatars,
  Titles, Utilities).
- Tap an item → detail sheet (or expandable card) showing:
  - Title, full description.
  - **How it was earned**: "Purchased on March 14, 2026" or "Earned
    from the 'Stone Cold Bluffer' achievement on March 12, 2026."
  - Equip / Unequip button (or "Equipped" indicator).
- Equipped state is per-category (one felt at a time, one card back,
  multiple emote packs unlocked simultaneously — see §4).

Today's stub: the shop's purchase confirmation sheet for an owned
item shows "Equip from Your Items in your profile (coming soon)"
inline. Once Your Items lands, swap the close-only CTA for "Manage in
Profile" → deep link to the relevant detail row.

## 4. Equip / unequip mechanics

Per category:

| Category | Equipped count | Drives |
|---|---|---|
| Felts | 1 active | Table background color |
| Card backs | 1 active | Hole card back art |
| Table themes | 1 active | Felt + rail (supersedes felt-only when both owned) |
| Emote packs | many active | Drawer of available emotes during a hand |
| Avatar packs | derived | User picks ONE specific emoji from any owned pack |
| Player titles | 1 active or none | Shown under the player's name at the table |
| Utilities (win-odds, opponent-style) | toggle each | Drives on-screen displays |

V1 data model already supports "owned" via `InventoryItem`. Equip
semantics need:
- New `equipped` table or `equipped: Boolean` column on `InventoryItem`.
- Per-category constraint (only one row can be equipped within most
  categories, multi-allowed for emote packs).
- Server-authoritative state once auth lands; client writes optimistic
  with the same reconciliation pattern as §2.

## 5. Achievement-earned items

A subset of items can be granted by achievement unlock, not purchase.
The inventory row has the same shape; only the `source` differs:

- `source = purchase` (chip or IAP)
- `source = achievement` (achievement id + unlock timestamp)
- `source = gift` (admin / promo / referral)

Server `InventoryItem` gains an `earnedSource` field. The achievement
engine writes inventory rows alongside XP awards on unlock. The
Profile / Your Items page (§3) reads + renders the source line.

## 6. Open questions

- **IAP refund handling**: if a player chargebacks an IAP, do we
  retroactively revoke the chips? Standard: yes, via a server-side
  pull from Apple's `subscriptions/v2/refundLookup` and Google's
  Voided Purchases API. Toast + nag for re-purchase, leave the
  account state alone otherwise.
- **Cross-device inventory** before auth: V1 is single-device. Sync
  happens after auth. Pre-auth users who reinstall lose their
  inventory unless we wire up a device-id-keyed soft account.
- **Sale-window honor** at IAP receipt time: if a player completes
  a flash-sale IAP a millisecond past the server's window, we
  currently honor it (platform store has the money). Long-term:
  reject the chip grant + initiate a platform-store refund. Cost:
  player frustration, platform-store rate limiting. Worth the
  trade-off only if abuse becomes meaningful.
