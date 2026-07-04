# In-flight (worker handoff log)

## refactor(billing): delete Dev/NoOp billing clients (BILL-6)

**Problem:** `DevBillingClient` and `NoOpBillingClient` were dead code — both platform bindings replaced them, and their own TODO said to remove them once real bindings landed.
**Approach:** Deleted both classes, moved `DEV_FAKE_CATALOG` into `FakeBillingClient.kt` (both platform clients still construct the fake from it in debug), dropped the now-empty `replaces=` lists, and rewrote the kdoc in `BillingClient`, `ProductsRepositoryImpl`, and `BillingAvailabilityImpl` that described the old default-binding story.
**Reviewer notes:** `docs/agent/compose-ui-testing-spike.md` still cites the Dev→NoOp replacement as a DI-swap example; left it as-is since it's a historical spike record. `developer-todo.md` also mentions `DevBillingClient` but workers can't touch that file.

## chore(pages): use the no-background app icon as the site logo (ENG-11)

**Problem:** The static site header logo used the old solid-background `app-icon.png`; a transparent-background icon now exists in Compose resources.
**Approach:** Downscaled `app_icon_no_background.png` (1024px) to 256px with `sips` — matching the old asset's dimensions since the CSS renders it at 96px — and overwrote `pages/app-icon.png`, so all three pages pick it up with no HTML changes. Kept `favicon.png` / `apple-touch-icon.png` on the opaque version deliberately: apple-touch icons must be opaque (iOS fills transparency with black) and a 64px favicon reads better with a solid ground.
**Reviewer notes:** Acceptance says "verified on the deployed Pages site" — deploy happens on merge via pages.yml, so verify post-merge. Rendered PNG checked locally (transparent rounded corners, RGBA).
