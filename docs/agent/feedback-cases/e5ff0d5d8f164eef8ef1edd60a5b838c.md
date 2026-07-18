# Feedback case e5ff0d5d8f164eef8ef1edd60a5b838c

- **Sentry issue:** https://elijah-dangerfield.sentry.io/issues/CARDS-AF (twin) · carrier CARDS-AE
- **Reported:** 2026-07-17T19:55:09Z · FeedbackRoute · beta-ios-release · cards@1.0+877 (35aa51a917ee)
- **Disposition:** todo: "[P2] AUTH-27 account deletion doesn't fully clear on-device preferences" (owner reclassified 2026-07-18)

## Bug description
> I just deleted my account and then went back through onboarding using Continue as Guest. I noticed that the shop didn't have the notification bubble on it like it should for new users… It makes me think that we're not fully clearing out preferences (reporter SlickEight36)

## IDs
- user: 7de9b42a (SlickEight36 same install) · session c53ff7f7-1a68-4e3c-befc-825ea175d7e5 · install c4a56e15

## Working theory / why no-action
After account deletion + re-onboard-as-guest, the shop "unseen products" dot was absent. Client log corroborates stale local state surviving the delete: `Dropped 1 orphan equipment row(s): [badge_founding_member_1000]` (tag InventorySync) and a 5-second `onboarding.completed` (reused local prefs) on the second pass. Root cause is that account deletion doesn't fully clear on-device preferences (the shop seen/unseen flag), so the new-user dot logic reads a prior "already seen" value. Real but low-severity cosmetic bug on a delete→re-onboard edge path. Owner reclassified 2026-07-18 as a real issue → filed **AUTH-27 [P2]**: account deletion must clear local per-user prefs (shop-seen, inventory cache, etc.) so a fresh guest onboarding shows true new-user state; add a delete→re-onboard regression test.
