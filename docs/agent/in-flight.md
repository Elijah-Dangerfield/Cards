## feat(qa-menu): show current user id with long-press copy

**Problem:** QA menu had no way to read the signed-in user's id for support / log correlation; existing config rows don't include identity context.
**Approach:** Added an optional `userId: String?` parameter to `QaMenuScreen`, rendered as a small `surfacePrimary` block between the description and "Clear all overrides" (above the config list). Long-press copies via `LocalClipboardManager.setText(AnnotatedString(...))` and shows an inline "Copied" hint that auto-clears after 1.5s. `ProfileFeatureEntryPoint` collects `identityRepository.state` and extracts `userId` from `IdentityState.SignedIn`.
**Reviewer notes:** Used `@Suppress("DEPRECATION")` on `LocalClipboardManager` — the new `LocalClipboard` exposes a suspend `setClipEntry(ClipEntry?)` API where `ClipEntry` construction is platform-specific in CMP 1.9.3 (no cross-platform `ClipEntry.withPlainText` factory shipped yet). Suppressing matches the existing pattern in `AudioRecorder.android.kt`. Migration to `LocalClipboard` becomes clean once CMP exposes a cross-platform `ClipEntry` builder.

## refactor(shop): drop "syncing" indicator from purchase flow

**Problem:** Owned-state UI (purchase sheet title + grid footer) leaked a "Syncing" affordance while the local pending-inventory row awaited server confirmation, contradicting the optimistic-purchase intent in docs/todo.md.
**Approach:** Removed the `pendingSync` field from both `PurchaseSheetMode.Owned` and `ChipOfferCardState.Owned` (both become `data object`). Dropped the pending-row inventory lookup in `ShopViewModel.sheetModeFor` / `classify`. Simplified `PurchaseConfirmSheet`'s owned title to "You own this" and `OwnedFooter` to always render "OWNED". Success path is unchanged — the snackbar already covers it.
**Reviewer notes:** No tests asserted on `pendingSync`, so no test churn beyond constructor calls. If the backend rejects an optimistic purchase, the current path surfaces the failure via `ShopEvent` / error snackbar — that remains untouched. Re-introducing a backend-rejected UI state would be additive on top.
