## refactor(room): migrate PotPill + WinOddsBadge to felt-accent surface

**Problem:** PotPill (BoardArea) and WinOddsBadge sit directly on the table felt but hardcoded `AppTheme.colors.surfaceSecondary`, so on non-default felts they read as alien chrome pasted on a colored background instead of "raised felt."
**Approach:** Wire both to `LocalFeltAccentSurface.current` with a fallback to `surfaceSecondary.color`, mirroring the pattern already used by `QuickActionBar`'s "more raise options" `IconButton`. Pure background swap — no layout change.
**Reviewer notes:** Visual change only; verifiable in Studio via `BoardAreaPreview_*` / `WinOddsBadgePreview_*` (those run with the default felt, so the previews look identical — felt-aware behavior only kicks in inside `PlayPokerScreen` where the local is provided). The todo's other felt-aware candidates (opponent chrome, explainer drag handles) are still open and remain in `docs/todo.md` under §A.
**Deferred:** OpponentsRow + explainer-sheet drag handle sweeps — todo updated to narrow the remaining surface to those two areas; another worker can pick them up.
