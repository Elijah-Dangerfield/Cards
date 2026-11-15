package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.logging.EXTRA_APP_EVENT
import com.dangerfield.cards.libraries.core.logging.LogEntry
import com.dangerfield.cards.libraries.core.logging.LogId
import com.dangerfield.cards.libraries.core.logging.LogTree

internal class RecordingRoomLogTree : LogTree() {
    val entries = mutableListOf<LogEntry>()

    override fun log(entry: LogEntry): LogId? {
        entries += entry
        return null
    }

    fun events(name: String): List<LogEntry> = entries.filter { it.context.extras[EXTRA_APP_EVENT] == name }
}
