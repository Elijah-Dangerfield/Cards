package com.dangerfield.cards.libraries.cards

interface Telemetry {
    fun initialize()

    fun setUser(
        email: String?,
        name: String?,
        id: String?
    )

    fun captureUserFeedback(
        message: String,
        isBugReport: Boolean,
        eventId: String?,
        errorCode: Int?
    )
}
