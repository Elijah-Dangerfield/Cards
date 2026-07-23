package com.dangerfield.cards.server.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hosted admin console: `/admin` serves the prebuilt bundle when it's on
 * disk, and its absence must never break boot (local runs have no bundle).
 */
class AdminWebTest {

    @Test
    fun servesConsoleWhenBundleExists() = testApplication {
        val dir = File.createTempFile("admin-web", null).let {
            it.delete()
            it.mkdirs().let { _ -> it }
        }
        File(dir, "index.html").writeText("<html><body>console</body></html>")
        File(dir, "cards-config-admin.js").writeText("// bundle")

        application { installAdminWeb(dir.absolutePath) }

        val index = client.get("/admin/")
        assertEquals(HttpStatusCode.OK, index.status)
        assertTrue(index.bodyAsText().contains("console"))
        // The entry page must revalidate so a redeployed console shows up
        // without a hard refresh.
        assertEquals("no-cache", index.headers["Cache-Control"])

        assertEquals(HttpStatusCode.OK, client.get("/admin/cards-config-admin.js").status)
    }

    @Test
    fun bareAdminPathRedirectsToSlash() = testApplication {
        val dir = File.createTempFile("admin-web", null).let {
            it.delete()
            it.mkdirs().let { _ -> it }
        }
        File(dir, "index.html").writeText("<html></html>")

        application { installAdminWeb(dir.absolutePath) }

        val response = client.config { followRedirects = false }.get("/admin")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/admin/", response.headers["Location"])
    }

    @Test
    fun missingBundleSkipsServingWithoutFailingBoot() = testApplication {
        application { installAdminWeb("/nonexistent/admin-web") }
        assertEquals(HttpStatusCode.NotFound, client.get("/admin/").status)
    }
}
