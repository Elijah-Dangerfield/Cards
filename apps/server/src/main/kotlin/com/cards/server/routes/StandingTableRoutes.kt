package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.RoomCodeAllocator
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.StandingTable
import com.dangerfield.cards.server.domain.StandingTablePatch
import com.dangerfield.cards.server.domain.StandingTableRepository
import com.dangerfield.cards.server.domain.StandingTableRules
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.isAnonymousUser
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

fun Route.standingTableRoutes(
    tables: StandingTableRepository,
    allocator: RoomCodeAllocator,
    rooms: RoomService,
    profiles: ProfileRepository,
    clock: Clock,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        route("/v1/standing-tables") {
            post {
                val user = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (call.isAnonymousUser()) {
                    return@post call.respond(HttpStatusCode.Forbidden, standingProblem("account_required", "Sign in to start a standing table."))
                }
                val body = call.receive<CreateStandingTableRequest>()
                val field = StandingTableRules.invalidField(body.name, body.buyIn, body.maxSeats, body.weekday, body.localTime, body.timeZone, body.durationMinutes)
                if (field != null) return@post call.respond(HttpStatusCode.BadRequest, standingProblem("bad_request", "$field is invalid."))
                if (listOfNotNull(body.feltProductId, body.cardBackProductId).any { it.length > 64 }) {
                    return@post call.respond(HttpStatusCode.BadRequest, standingProblem("bad_request", "cosmetics must be at most 64 characters."))
                }
                val table = allocator.withCode { code ->
                    if (tables.countActiveOwnedBy(user) >= 3) return@withCode null
                    val now = clock.now()
                    StandingTable(code, user, StandingTableRules.normalizedName(body.name), body.buyIn, body.maxSeats,
                        DayOfWeek.entries[body.weekday - 1], LocalTime.parse(body.localTime), TimeZone.of(body.timeZone),
                        body.durationMinutes, body.feltProductId?.trim()?.ifEmpty { null }, body.cardBackProductId?.trim()?.ifEmpty { null }, now, now)
                        .also { tables.create(it) }
                } ?: return@post call.respond(HttpStatusCode.Conflict, standingProblem("too_many_standing_tables", "You already have 3 standing tables."))
                call.respond(HttpStatusCode.Created, table.toDto(user, profiles.findOrCreate(user).displayName, 0, null, clock.now()))
            }
            get("/mine") {
                val user = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val now = clock.now()
                val result = tables.listForUser(user).map { table ->
                    table.toDto(user, profiles.ownerName(table), tables.regularCount(table.code), rooms.findLoaded(table.code), now)
                }.sortedWith(compareByDescending<StandingTableDto> { it.isOwner }.thenBy { it.nextStartsAt })
                call.respond(StandingTablesResponse(tables = result))
            }
            get("/{code}") {
                val user = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val table = call.parameters["code"]?.uppercase()?.let { tables.find(it) }
                    ?: return@get call.respond(HttpStatusCode.NotFound, standingProblem("standing_table_not_found", "Standing table not found."))
                call.respond(table.toDto(user, profiles.ownerName(table), tables.regularCount(table.code), rooms.findLoaded(table.code), clock.now()))
            }
            patch("/{code}") {
                val user = call.userId() ?: return@patch call.respond(HttpStatusCode.Unauthorized)
                val table = call.parameters["code"]?.uppercase()?.let { tables.find(it) }?.takeUnless { it.isArchived }
                    ?: return@patch call.respond(HttpStatusCode.NotFound, standingProblem("standing_table_not_found", "Standing table not found."))
                if (table.ownerUserId != user) return@patch call.respond(HttpStatusCode.Forbidden, standingProblem("not_owner", "Only the owner can edit this table."))
                val body = call.receive<PatchStandingTableRequest>()
                val name = body.name ?: table.name
                val buyIn = body.buyIn ?: table.buyIn
                val seats = body.maxSeats ?: table.maxSeats
                val weekday = body.weekday ?: (table.weekday.ordinal + 1)
                val time = body.localTime ?: "%02d:%02d".format(table.localTime.hour, table.localTime.minute)
                val zone = body.timeZone ?: table.timeZone.id
                val duration = body.durationMinutes ?: table.durationMinutes
                val field = StandingTableRules.invalidField(name, buyIn, seats, weekday, time, zone, duration)
                if (field != null) return@patch call.respond(HttpStatusCode.BadRequest, standingProblem("bad_request", "$field is invalid."))
                val updated = tables.update(table.code, StandingTablePatch(
                    name = body.name?.let(StandingTableRules::normalizedName),
                    buyIn = body.buyIn,
                    maxSeats = body.maxSeats,
                    weekday = body.weekday?.let { DayOfWeek.entries[it - 1] },
                    localTime = body.localTime?.let(LocalTime::parse),
                    timeZone = body.timeZone?.let(TimeZone::of),
                    durationMinutes = body.durationMinutes,
                    updatedAt = clock.now(),
                )) ?: return@patch call.respond(HttpStatusCode.NotFound,
                    standingProblem("standing_table_not_found", "Standing table not found."))
                rooms.refreshStandingTable(table.code)
                call.respond(updated.toDto(user, profiles.ownerName(table), tables.regularCount(table.code), rooms.findLoaded(table.code), clock.now()))
            }
            delete("/{code}") {
                val user = call.userId() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val table = call.parameters["code"]?.uppercase()?.let { tables.find(it) }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, standingProblem("standing_table_not_found", "Standing table not found."))
                if (table.ownerUserId != user) return@delete call.respond(HttpStatusCode.Forbidden, standingProblem("not_owner", "Only the owner can delete this table."))
                tables.archive(table.code, clock.now())
                rooms.refreshStandingTable(table.code)
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/{code}/regulars/me") {
                val user = call.userId() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val table = call.parameters["code"]?.uppercase()?.let { tables.find(it) }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, standingProblem("standing_table_not_found", "Standing table not found."))
                tables.removeRegular(table.code, user)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private suspend fun ProfileRepository.ownerName(table: StandingTable): String =
    table.ownerUserId?.let { findById(it)?.displayName }.orEmpty()

private fun standingProblem(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))
