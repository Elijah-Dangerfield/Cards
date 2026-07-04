# In-flight log

## style(room): route room spinners through the DS CircularProgressIndicator (GAME-18)

**Problem:** Two callsites in `:features:room:impl` imported `androidx.compose.material3.CircularProgressIndicator` directly instead of the DS wrapper, so they rendered Material's default color instead of `accentPrimary`.
**Approach:** Swapped the imports to `com.dangerfield.cards.libraries.ui.components.CircularProgressIndicator` (same call shape, defaults come from the DS). No callsite logic changed.
**Reviewer notes:** None.

## refactor(ui): route ImagePicker decode through LocalDispatcherProvider (ENG-13)

**Problem:** `rememberImagePicker`'s Android actual called `withContext(Dispatchers.Default)` directly, breaking the never-touch-raw-`Dispatchers` rule (the decode can't be virtualized onto a test scheduler).
**Approach:** Added `LocalDispatcherProvider` to the DS locals (`libraries/ui/.../system/Local.kt`), defaulting to `DefaultDispatcherProvider` so previews work unprovided, and provided the DI-bound instance at the app root in `App.kt` (new `dispatcherProvider` accessor on `AppComponent`). Rejected the alternative of adding a `DispatcherProvider` param to the `rememberImagePicker` expect signature — it would cascade through `ScreenshotAttachmentField` and every callsite for a cross-cutting concern. Bumped `:libraries:flowroutines` to `api` in `:libraries:ui` since the local exposes the type.
**Reviewer notes:** The composition local's default is the real `DefaultDispatcherProvider`, not a noop — a preview that actually launches the picker would use real dispatchers, which matches the old behavior. UI tests that want scheduler control provide `TestDispatcherProvider` via the local.

## fix(resources): sweep em dashes out of rank/XP explainer copy (ENG-12)

**Problem:** 11 strings in the rank and XP explainer copy used em dashes, which AGENTS.md bans in user-facing copy.
**Approach:** Rephrased each with plain commas/periods or restructured the sentence (e.g. the earn-XP bullets became plain sentences: "Every hand you finish counts, even quick folds") rather than mechanically swapping the dash for a colon, per the unslop-text guidance. Voice stays warm and casual. The one remaining non-hyphen dash in strings.xml is the `room_action_stepper_decrement` "–" glyph, which is the affordance itself (glyph-only exception).
**Reviewer notes:** The four `stats_explainer_earn_bullet_*` strings dropped the "label — elaboration" shape for plain sentences; worth a glance in the sheet UI to confirm the bullets still scan well as a list.

## docs(wiki): point wallet key-files at ChipsRepositoryImpl (ENG-15)

**Problem:** `docs/wiki/wallet.md` listed a `ChipsSync` key file that doesn't exist anywhere in the repo.
**Approach:** Replaced it with the real path (`libraries/cards/impl/.../ChipsRepositoryImpl.kt`) and named the `sync()` / `syncLocked()` entry points so the pointer survives line drift better than a bare line range.
**Reviewer notes:** None.
