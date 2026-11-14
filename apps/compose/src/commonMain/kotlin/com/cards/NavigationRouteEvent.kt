package com.dangerfield.cards

internal fun nextRouteEvent(previous: String?, current: String?): Pair<String, String?>? =
    current?.takeIf { it != previous }?.let { it to previous }
