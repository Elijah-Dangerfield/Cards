package com.dangerfield.cards.libraries.ui.system

/**
 * Marker for the raw, DS-unopinionated layer of a design-system component
 * family. The opinionated default (e.g. `Dialog`, `BottomSheet`, future
 * `Banner`, `Tooltip`, …) wraps a `Base*` primitive that owns the
 * mechanics but applies no surface, padding, or typography decisions —
 * opting in to that primitive is opting *out* of the DS defaults.
 *
 * Reach for a `Base*` only for genuine one-offs (custom animation,
 * full-bleed marketing surface, non-standard shape). For 99% of usage
 * the unadorned name (`Dialog(...)`, `BottomSheet(...)`) is the right
 * call — its defaults are what keep screens visually coherent.
 *
 * Warning level is intentional: the escape hatch needs to stay reachable
 * when it's genuinely needed. Error-level friction pushes callers back
 * to hand-rolled `Box { background, clip, padding }`, which is worse
 * than the original problem.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Low-level DS primitive. Prefer the opinionated wrapper (Dialog, BottomSheet, etc.) — " +
        "opt in only for genuine one-offs that need to escape the DS defaults.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class LowLevelDSComponent
