package com.dangerfield.cards.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Serves the remote-config admin console (the prebuilt `apps/admin` JS bundle)
 * at `/admin`. The deploy workflows build the bundle on the CI runner and the
 * Docker image copies it in — the server build itself never grows a JS
 * toolchain (see apps/server/Dockerfile).
 *
 * The page is public but inert: every API call it can make is gated by
 * `X-Admin-Token`, and the bundle holds no secrets (tokens are pasted at
 * runtime). A missing/empty [dirPath] just logs and skips, so local
 * `:apps:server:run` without a bundle keeps working.
 */
fun Application.installAdminWeb(dirPath: String) {
    val dir = File(dirPath)
    val log = LoggerFactory.getLogger("AdminWeb")
    if (!File(dir, "index.html").isFile) {
        log.info("No admin console bundle at ${dir.absolutePath} — /admin not served")
        return
    }
    log.info("Serving admin console from ${dir.absolutePath} at /admin")
    routing {
        get("/admin") { call.respondRedirect("/admin/") }
        staticFiles("/admin", dir) {
            default("index.html")
            // The bundle filename isn't content-hashed; keep the entry page
            // revalidating so a redeployed console shows up without a hard
            // refresh (the JS still gets ETag/Last-Modified revalidation).
            modify { file, call ->
                if (file.name == "index.html") {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                }
            }
        }
    }
}
