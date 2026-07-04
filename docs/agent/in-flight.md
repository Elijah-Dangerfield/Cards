# In-flight log

## style(room): route room spinners through the DS CircularProgressIndicator (GAME-18)

**Problem:** Two callsites in `:features:room:impl` imported `androidx.compose.material3.CircularProgressIndicator` directly instead of the DS wrapper, so they rendered Material's default color instead of `accentPrimary`.
**Approach:** Swapped the imports to `com.dangerfield.cards.libraries.ui.components.CircularProgressIndicator` (same call shape, defaults come from the DS). No callsite logic changed.
**Reviewer notes:** None.
