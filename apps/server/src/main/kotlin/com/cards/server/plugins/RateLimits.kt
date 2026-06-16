package com.dangerfield.cards.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.request.header
import io.ktor.server.request.path
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Named rate-limit buckets. Routes opt in via
 * `rateLimit(RateLimitName(NAME)) { ... }`. Limits are deliberately loose
 * for V1 — they protect against accidental hot loops and the rare bad
 * actor, not concerted DoS (Fly's edge + Cloudflare in front of it own
 * that layer).
 *
 * Keying strategy: per-IP. We don't extract the JWT `sub` because the
 * rate-limit plugin runs BEFORE the auth plugin's `validate` hook, so
 * authenticated principals aren't populated yet. IP works for V1; a
 * future per-user policy would need a custom plugin or running the limiter
 * inside the `authenticate { … }` block (Ktor allows it but the plumbing
 * is awkward).
 *
 * `/_health` is excluded globally so the Fly health-check probe doesn't
 * eat the bucket.
 */
const val DELETE_ACCOUNT_LIMIT = "delete-account"
const val PROFILE_WRITE_LIMIT = "profile-write"
const val WALLET_WRITE_LIMIT = "wallet-write"
const val PROGRESSION_WRITE_LIMIT = "progression-write"
const val ACHIEVEMENTS_WRITE_LIMIT = "achievements-write"
const val ACHIEVEMENT_GRANT_LIMIT = "achievement-grant"

fun Application.installRateLimits() {
    install(RateLimit) {
        global {
            // 600 req / IP / minute is generous — a normal client never
            // approaches it. Catches accidental loops and trivial scrapers.
            rateLimiter(limit = 600, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp() }
            requestWeight { call, _ ->
                if (call.request.path().startsWith("/_health")) 0 else 1
            }
        }

        register(RateLimitName(DELETE_ACCOUNT_LIMIT)) {
            // App Store review explicitly cites brute-force / harassment as
            // a deletion-endpoint risk. 5/hour per IP gives a legitimate
            // user multiple retries while making scripted abuse impractical.
            rateLimiter(limit = 5, refillPeriod = 1.hours)
            requestKey { call -> call.clientIp() }
        }

        register(RateLimitName(PROFILE_WRITE_LIMIT)) {
            // PATCH /v1/me has a unique-display-name constraint server-side.
            // 30/hour gives the user plenty of retries while making
            // name-squatting bots expensive.
            rateLimiter(limit = 30, refillPeriod = 1.hours)
            requestKey { call -> call.clientIp() }
        }

        register(RateLimitName(ACHIEVEMENTS_WRITE_LIMIT)) {
            // POST /v1/me/achievements/sync fires on the same cadence as the
            // wallet/progression syncs (cold boot, foreground, after hands that
            // unlock achievements). Same 480/hour headroom + per-IP keying.
            rateLimiter(limit = 480, refillPeriod = 1.hours)
            requestKey { call -> call.clientIp() }
        }

        register(RateLimitName(ACHIEVEMENT_GRANT_LIMIT)) {
            // POST /v1/me/grants/achievement/{id} fires from the client when a
            // bot-mode achievement criterion lights up at hand-end. Real
            // clients hit it once per (user, achievement) for life — most
            // accounts fire it zero times most hours, a new user clearing
            // volume/showdown/quality achievements in their first session
            // might burst 8-12 grants in an hour. 120/hour/IP keeps a 10x
            // ceiling over that natural high-water mark and a shared NAT
            // with two heavy users still fits comfortably, while a scripted
            // flood gets capped at 2/minute sustained.
            rateLimiter(limit = 120, refillPeriod = 1.hours)
            requestKey { call -> call.clientIp() }
        }

        register(RateLimitName(PROGRESSION_WRITE_LIMIT)) {
            // POST /v1/me/progression/sync fires on the same cadence as the
            // wallet sync (cold boot, foreground resume, opportunistically
            // after hands award XP). Same 480/hour headroom + per-IP keying.
            rateLimiter(limit = 480, refillPeriod = 1.hours)
            requestKey { call -> call.clientIp() }
        }

        register(RateLimitName(WALLET_WRITE_LIMIT)) {
            // POST /v1/me/wallet/sync runs once per cold boot, once on
            // foreground resume, plus opportunistically after each chip
            // movement. A heavy user playing 200 hands could plausibly
            // hit 250+ syncs in an hour; 480/hour gives ~8 syncs/minute
            // headroom while still capping abuse at one batch every
            // 7.5 seconds sustained. Per-IP keying mirrors the rest of
            // the policy — a future per-user-id refinement (see file
            // header) would let one user on a shared NAT not exhaust
            // the bucket for everyone else.
            rateLimiter(limit = 480, refillPeriod = 1.hours)
            requestKey { call -> call.clientIp() }
        }
    }
}

/**
 * Best-effort client IP. Trusts the standard reverse-proxy headers Fly
 * sets on inbound requests (`Fly-Client-IP` first, then `X-Forwarded-For`,
 * then the socket address). Order matters: the wrong choice would mean
 * everyone shares Fly's edge IP and the limiter degenerates to a single
 * global bucket.
 *
 * `internal` so [`ClientIpTest`] can pin the precedence directly via a
 * tiny test route — the alternative (driving traffic through the full
 * `installRateLimits` plugin and inspecting the limiter's bucket keying)
 * tests the same logic with three extra moving parts.
 */
internal fun io.ktor.server.application.ApplicationCall.clientIp(): String {
    request.header("Fly-Client-IP")?.takeIf { it.isNotBlank() }?.let { return it }
    request.header("X-Forwarded-For")?.split(',')?.firstOrNull()?.trim()
        ?.takeIf { it.isNotBlank() }?.let { return it }
    return request.local.remoteHost
}
