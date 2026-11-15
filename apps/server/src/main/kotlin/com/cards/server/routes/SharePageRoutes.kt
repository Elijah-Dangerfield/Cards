package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.StandingTable
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.StandingTableRepository
import com.dangerfield.cards.server.plugins.SHARE_PAGE_LIMIT
import com.dangerfield.cards.server.plugins.ShareOutcome
import com.dangerfield.cards.server.plugins.ShareSurface
import com.dangerfield.cards.server.plugins.measureShareRequest
import com.dangerfield.cards.server.share.IcsBuilder
import com.dangerfield.cards.server.share.PreviewImageRenderer
import com.dangerfield.cards.server.share.SharePageRenderer
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.host
import io.ktor.server.routing.route
import kotlin.time.Clock

private val shareCodePattern = Regex("^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$")

fun Route.sharePageRoutes(
    tables: StandingTableRepository,
    rooms: RoomService,
    profiles: ProfileRepository,
    clock: Clock = Clock.System,
    renderer: PreviewImageRenderer = PreviewImageRenderer(),
) {
    rateLimit(RateLimitName(SHARE_PAGE_LIMIT)) {
        get("/j/assets/body.ttf") {
            call.respondBytes(shareResource("/share/fonts/Roboto-Medium.ttf"), ContentType("font", "ttf"))
        }
        get("/j/assets/app-icon.png") {
            call.respondBytes(shareResource("/share/app-icon.png"), ContentType.Image.PNG)
        }
        inviteRoutes("/j/{code}", tables, rooms, profiles, clock, renderer)
        host("join.downcard.app") {
            inviteRoutes("/{code}", tables, rooms, profiles, clock, renderer)
        }
    }
}

private fun Route.inviteRoutes(
    path: String,
    tables: StandingTableRepository,
    rooms: RoomService,
    profiles: ProfileRepository,
    clock: Clock,
    renderer: PreviewImageRenderer,
) {
    route(path) {
        get {
            measureShareRequest(ShareSurface.Page) {
                val code = call.validShareCode() ?: return@measureShareRequest call.invalidShareCode()
                val target = resolveShareTarget(code, tables, rooms)
                val owner = target.table?.let { profiles.findById(it.ownerUserId)?.displayName }.orEmpty()
                call.respondText(
                    SharePageRenderer.render(code, target.table, target.room, owner, call.shareUrl(code), clock.now()),
                    ContentType.Text.Html,
                )
                target.outcome
            }
        }
        get("/calendar.ics") {
            measureShareRequest(ShareSurface.Calendar) {
                val code = call.validShareCode() ?: return@measureShareRequest call.invalidShareCode()
                val target = resolveShareTarget(code, tables, rooms)
                val table = target.table
                if (table == null) {
                    call.notFound()
                    return@measureShareRequest target.outcome
                }
                val filename = table.name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_") + ".ics"
                call.response.header(HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString())
                call.respondText(IcsBuilder.build(table, call.shareUrl(code), clock.now()), ContentType("text", "calendar"))
                target.outcome
            }
        }
        get("/preview.png") {
            measureShareRequest(ShareSurface.Preview) {
                val code = call.validShareCode() ?: return@measureShareRequest call.invalidShareCode()
                val target = resolveShareTarget(code, tables, rooms)
                call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
                call.respondBytes(renderer.render(code, target.table, clock.now()), ContentType.Image.PNG)
                target.outcome
            }
        }
    }
}

private data class ShareTarget(val table: StandingTable?, val room: Room?, val outcome: ShareOutcome)

private suspend fun resolveShareTarget(code: String, tables: StandingTableRepository, rooms: RoomService): ShareTarget {
    val storedTable = tables.find(code)
    val table = storedTable?.takeIf { it.archivedAt == null }
    val room = if (storedTable?.archivedAt == null) rooms.findLoaded(code) else null
    val outcome = when {
        storedTable?.archivedAt != null -> ShareOutcome.Archived
        table != null -> ShareOutcome.Standing
        room != null -> ShareOutcome.Ordinary
        else -> ShareOutcome.Unknown
    }
    return ShareTarget(table, room, outcome)
}

private suspend fun ApplicationCall.invalidShareCode(): ShareOutcome {
    notFound()
    return ShareOutcome.Invalid
}

private fun ApplicationCall.validShareCode(): String? = parameters["code"]?.uppercase()?.takeIf(shareCodePattern::matches)

private suspend fun ApplicationCall.notFound() {
    respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
}

private fun ApplicationCall.shareUrl(code: String): String {
    val host = request.host()
    val origin = if (host == "127.0.0.1" || host == "localhost") "http://$host:${request.port()}" else "https://$host"
    return if (host == "join.downcard.app") "$origin/$code" else "$origin/j/$code"
}

private fun shareResource(path: String): ByteArray =
    checkNotNull(PreviewImageRenderer::class.java.getResourceAsStream(path)).use { it.readBytes() }
