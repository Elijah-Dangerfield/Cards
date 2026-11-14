package com.dangerfield.cards.server.share

import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.StandingTable
import kotlin.time.Instant

internal object SharePageRenderer {
    private val template = checkNotNull(javaClass.getResource("/share/page.html")).readText()
    private val placeholders = Regex("\\{\\{(\\w+)}}")

    fun render(code: String, table: StandingTable?, room: Room?, owner: String, shareUrl: String, now: Instant): String {
        val title = when {
            table != null -> "${table.name} · Downcard"
            room != null -> "Join my poker game on Downcard"
            else -> "This table isn't open right now · Downcard"
        }
        val description = when {
            table != null -> "Every ${weekdayName(table)} at ${timeAndZone(table, now)}. Room code $code. Tap to grab a seat."
            room != null -> "Room code $code. ${room.members.size} seated. Tap to join."
            else -> "This table isn't open right now. Get Downcard and try the code when your friends are ready."
        }
        val calendar = table?.let {
            """<div class="calendar"><a id="calendar" href="${escapeHtml(googleCalendarUrl(it, shareUrl, now))}" data-ics="${escapeHtml("$shareUrl/calendar.ics")}">Add to calendar</a><a id="download" href="${escapeHtml("$shareUrl/calendar.ics")}">Download .ics</a></div>"""
        }.orEmpty()
        val values = mapOf(
            "title" to escapeHtml(title),
            "heading" to escapeHtml(table?.name ?: if (room != null) "Join my poker game" else "This table isn't open right now"),
            "description" to escapeHtml(description),
            "owner" to if (table != null) "<p>${escapeHtml(owner)}'s table</p>" else "",
            "code" to escapeHtml(code),
            "shareUrl" to escapeHtml(shareUrl),
            "imageUrl" to escapeHtml("$shareUrl/preview.png"),
            "calendar" to calendar,
        )
        return placeholders.replace(template) { checkNotNull(values[it.groupValues[1]]) }
    }
}

internal fun escapeHtml(value: String): String = value.replace("&", "&amp;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
