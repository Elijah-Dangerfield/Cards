package com.dangerfield.cards.libraries.ui.components.dialog

@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Low-level dialog/sheet primitive. Prefer `Dialog(...)` or `BottomSheet(...)` — the DS-opinionated wrappers. " +
        "Opt in only when intentionally escaping the DS defaults (custom animation, non-DS surface).",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class LowLevelDialogApi
