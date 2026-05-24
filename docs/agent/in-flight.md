## fix(profile): unclip lock badge on locked avatar tiles

**Problem:** Locked avatar tiles in Edit Profile's pack picker had the bottom-right of the 🔒 chip cut off — the badge sat inside the `BoxWithConstraints` that owned the circular `.clip(CircleShape)`, so its corner was carved away with the tile background.
**Approach:** Wrapped `AvatarTile` in an unclipped outer `Box`, moved the existing `BoxWithConstraints` (with the circle clip + background + border + click) inside it, and overlaid the lock badge as a sibling at `Alignment.BottomEnd` of the outer box. The inner circle still clips the emoji + 0.35 alpha overlay so the dim-locked treatment is unchanged. Dropped the previous 4dp inner padding on the badge — it was carving the chip away from the corner specifically to dodge the clip, no longer needed.
**Reviewer notes:** Visual verification was build-only (`:apps:compose:compileDebugKotlinAndroid` clean). The pack picker doesn't currently have its own `@Preview` to eyeball — first locked-pack render on device will confirm.

