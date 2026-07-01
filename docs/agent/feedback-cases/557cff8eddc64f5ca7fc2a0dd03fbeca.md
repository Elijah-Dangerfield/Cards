# Feedback case 557cff8eddc64f5ca7fc2a0dd03fbeca

- **Sentry issue:** https://elijah-dangerfield.sentry.io/issues/CARDS-6G (carrier) · https://elijah-dangerfield.sentry.io/issues/CARDS-6H (feedback twin)
- **Reported:** 2026-06-30T17:01:51Z · FeedbackRoute · dev-ios-debug · cardse@0.1.0+1
- **Disposition:** todo: "SHOP-4 default card back + felt shouldn't show an 'earned today' badge"

## Bug description
> the default card back and default felt always say "earned today" maybe just remove that?

(QuickQueen52, no email)

## IDs
- user: 0269a06a-2b8f-41fc-aa68-6777198c9ddf (QuickQueen52, anon)
- session: 8fbab7c8-5573-4ca8-802f-cffc30caf1be
- install: 61e95306-2d91-4cad-b82b-a7f795d2d9eb
- non-MP — no room_code

## Reporter client log
Breadcrumbs: user finished a solo bots hand, returned to Home, switched to Profile (`switchTab to ProfileRoute`, `fetchAvatarPack Success (6 packs, 8 colors)`, `Equipment sync complete: 2 server-equipped`) then opened Feedback. The report is about the equipped-cosmetics / My-Items presentation, viewed on the Profile tab.

## Client state at submit
Non-MP; no client-state.json. Viewing equipped items (2 server-equipped: the default card back + default felt).

## Server activity
- Loki (session 8fbab7c8, 16:46–17:16Z, warn|error): zero rows. Static UI-label bug; no server involvement.

## Working theory
The "Earned" / "earned today" badge (added in the My-Items earned-badge work; see backlog "Earn-source attribution on My Items 'Earned' rows") is being rendered on the default, always-owned card back and felt. Defaults are granted at account creation, so an `earnedAt == today` (or a null/default timestamp resolving to "today") makes the badge fire for freebies the user never "earned." The badge should be suppressed for default/starter cosmetics (or for items without a real earn-source), leaving it only on genuinely earned unlocks. Root cause is in the equipped-item / My-Items badge predicate — no telemetry needed; it's a static presentation rule. Small, well-scoped → P2.
