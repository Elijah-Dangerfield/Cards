package com.dangerfield.cards.libraries.cards

@RequiresOptIn(
    message = "This code should not be called during blocking start up tasks",
    level = RequiresOptIn.Level.WARNING // or ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS
)
annotation class StartUpWarning