# In-flight

Handoff log for the current cycle. One block per commit. The reviewer reads this when writing the PR, then deletes the file.

## feat: badge the Profile settings gear with the unread count

**Problem:** The unread-notifications count only surfaced on the Profile bottom-tab and the in-Settings "Notifications" row, but the actual path to the inbox is the top-bar gear — a user already on Profile got no signal there was something to read.
**Approach:** Lifted a `BadgedIconButton` primitive into `:libraries:ui` (wraps `IconButton` in the existing `BadgedBox`, mirroring the bottom-tab badge language: numbered pill for `badgeCount > 0`, bare dot for `showDot`, both defaulting off). Plumbed `observeUnreadInboxCount()` into the `ProfileRoute` block's `ProfileSettings` (it was only wired into `SettingsRoute` before) and swapped the gear's plain `IconButton` for the badged one.
**Reviewer notes:** Badge clears when the inbox is opened because it's reactive off the same `observeUnreadInboxCount()` flow the Settings row uses — no extra clear logic. Visual placement (`DpOffset(-4,4)`) eyeballed against the bottom-tab's `(-5,5)` but not rendered in Studio; worth a glance against the new `BadgedIconButtonPreview_Count`.
