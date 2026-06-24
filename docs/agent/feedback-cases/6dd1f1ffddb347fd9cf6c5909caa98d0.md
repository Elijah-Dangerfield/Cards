# Feedback case 6dd1f1ffddb347fd9cf6c5909caa98d0

- **Sentry issue:** https://elijah-dangerfield.sentry.io/issues/CARDS-2N (feedback twin); carrier https://elijah-dangerfield.sentry.io/issues/CARDS-2M
- **Reported:** 2026-06-24T12:38:33 · FeedbackRoute · dev-ios-debug · cardse@0.1.0+1
- **Disposition:** todo: "[P1] Leaving a bots-for-chips MP table doesn't bring your chips; next-hand button dead on the you-won dialog"

## Bug description
> i just left a multi play game against a bot that should've been for real chips. I won the first hand at 9k chips or something. When I left the chips didn't go with me. Also on the "you won" round thing the next hand button did nothing. I had to click outside of the dialog to close it.

## IDs
- user: 94ad4fdc-c36b-48b4-89c1-dbae65803ba6 (SharpJack91)
- session: 6d07773d-55d5-4183-a074-acfa49abee14
- install: 82b59ecd-b901-438b-a33b-0be45478f64d
- MP context: room MZJMA5 (the bot table for the earlier hand), later also S3XG9M

## Reporter client log
session-log.txt (cumulative): joined MZJMA5 12:35:03 (hand 1, 4 seats), played a hand (calls/checks/Bet 50) 12:37:04–12:37:32, recorded play-style hand 3, then navigated Home 12:37:43. Submitted this feedback 12:38:33. The "next hand did nothing" is consistent with the known hand-end-stall family (RequestNextHand not advancing).

## Server activity
- Loki room MZJMA5: `Hand 3 finished — session=55da2281…` at 12:37:32. On leave (DELETE /rooms/MZJMA5/me 204 at 12:37:43):
  `bot_subsidy_payout user=94ad4fdc… amount=4475 grantedWindow=4475 cap=25000` (DefaultTableSessionService.cashOut). Then `Room MZJMA5 closed: last member … left`.
- So the server paid a capped *bot subsidy* of 4475 on cash-out, NOT the table stack the user saw (~9k). That mismatch is exactly the "chips didn't go with me" complaint — the bots-for-chips cashout pays a subsidy windowed to a cap, not the chips displayed at the table.

## Working theory
Two issues bundled: (1) the bots-for-chips cashout (`DefaultTableSessionService.cashOut` → `bot_subsidy_payout`, capped at 25000, granted 4475 here) pays a subsidy that doesn't match the chip total the player watched themselves win at the table, so leaving "loses" chips from the player's perspective — needs either a UX that explains the subsidy model or a settlement that matches the displayed stack. (2) The "next hand" button on the you-won dialog did nothing (had to tap outside to dismiss) — same RequestNextHand hand-end-stall family already tracked as P0 (CARDS-25 / CARDS-16). File the chips/cashout half as its own P1; the next-hand-button half is a dup of the hand-end stall.
