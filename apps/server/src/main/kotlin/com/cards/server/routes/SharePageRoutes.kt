package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.StandingTableRepository
import com.dangerfield.cards.server.plugins.SHARE_PAGE_LIMIT
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
            val code = call.validShareCode() ?: return@get call.notFound()
            val storedTable = tables.find(code)
            val table = storedTable?.takeIf { it.archivedAt == null }
            val room = if (storedTable?.archivedAt == null) rooms.findLoaded(code) else null
            val owner = table?.let { profiles.findById(it.ownerUserId)?.displayName }.orEmpty()
            call.respondText(
                SharePageRenderer.render(code, table, room, owner, call.shareUrl(code), clock.now()),
                ContentType.Text.Html,
            )
        }
        get("/calendar.ics") {
            val code = call.validShareCode() ?: return@get call.notFound()
            val table = tables.find(code)?.takeIf { it.archivedAt == null } ?: return@get call.notFound()
            val filename = table.name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_") + ".ics"
            call.response.header(HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString())
            call.respondText(IcsBuilder.build(table, call.shareUrl(code), clock.now()), ContentType("text", "calendar"))
        }
        get("/preview.png") {
            val code = call.validShareCode() ?: return@get call.notFound()
            val table = tables.find(code)?.takeIf { it.archivedAt == null }
            call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
            call.respondBytes(renderer.render(code, table, clock.now()), ContentType.Image.PNG)
        }
    }
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
