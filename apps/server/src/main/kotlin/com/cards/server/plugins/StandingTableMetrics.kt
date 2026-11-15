package com.dangerfield.cards.server.plugins

import com.dangerfield.cards.libraries.core.Catching

internal enum class ShareSurface(val label: String) { Page("page"), Calendar("calendar"), Preview("preview") }
internal enum class ShareOutcome(val label: String) {
    Standing("standing"), Ordinary("ordinary"), Unknown("unknown"), Archived("archived"), Invalid("invalid"), Error("error"),
}
internal enum class StandingJoinOutcome(val label: String) {
    Joined("joined"), AlreadyJoined("already_joined"), Archived("archived"), InsufficientBalance("insufficient_balance"),
    Full("full"), NotJoinable("not_joinable"), NotFound("not_found"), Error("error"),
}
internal enum class StandingMaterializationOutcome(val label: String) { Created("created"), Reused("reused"), Error("error") }

internal suspend fun measureShareRequest(surface: ShareSurface, block: suspend () -> ShareOutcome) {
    Catching { block() }
        .onSuccess { ServerMetrics.recordShareRequest(surface, it) }
        .onFailure { ServerMetrics.recordShareRequest(surface, ShareOutcome.Error) }
        .getOrThrow()
}

internal suspend fun measureStandingJoin(isStanding: Boolean, block: suspend () -> StandingJoinOutcome) {
    Catching { block() }
        .onSuccess { if (isStanding) ServerMetrics.recordStandingJoin(it) }
        .onFailure { if (isStanding) ServerMetrics.recordStandingJoin(StandingJoinOutcome.Error) }
        .getOrThrow()
}
