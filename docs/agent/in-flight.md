# In-flight log

## style(room): route room spinners through the DS CircularProgressIndicator (GAME-18)

**Problem:** Two callsites in `:features:room:impl` imported `androidx.compose.material3.CircularProgressIndicator` directly instead of the DS wrapper, so they rendered Material's default color instead of `accentPrimary`.
**Approach:** Swapped the imports to `com.dangerfield.cards.libraries.ui.components.CircularProgressIndicator` (same call shape, defaults come from the DS). No callsite logic changed.
**Reviewer notes:** None.

## docs(wiki): point wallet key-files at ChipsRepositoryImpl (ENG-15)

**Problem:** `docs/wiki/wallet.md` listed a `ChipsSync` key file that doesn't exist anywhere in the repo.
**Approach:** Replaced it with the real path (`libraries/cards/impl/.../ChipsRepositoryImpl.kt`) and named the `sync()` / `syncLocked()` entry points so the pointer survives line drift better than a bare line range.
**Reviewer notes:** None.
