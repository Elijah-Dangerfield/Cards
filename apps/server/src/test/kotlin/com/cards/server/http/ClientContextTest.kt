package com.dangerfield.cards.server.http

import com.dangerfield.cards.server.plugins.installSerialization
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the [ApplicationCall.clientContext] header parser. Every authenticated
 * route reads this — a regression on platform resolution, country-code
 * filtering, or Accept-Language fallback silently degrades the request
 * context every endpoint downstream sees (catalog locale match, audit
 * fields, future per-platform behavior).
 *
 * The parser is an `ApplicationCall` extension, so the tests stand up a
 * minimal `testApplication` with a single `/echo-context` route that
 * serializes the parsed context into a JSON DTO and asserts the
 * round-trip.
 */
class ClientContextTest {

    @Serializable
    private data class EchoDto(
        val platform: String = "",
        val appVersion: String? = null,
        val buildNumber: Int? = null,
        val preferredLocales: List<String> = emptyList(),
        val countryCode: String? = null,
    )

    @Test
    fun parsesIosPlatform_caseInsensitively() = runTest {
        echo(
            headers = mapOf(ClientContext.HEADER_PLATFORM to "iOS"),
        ) { dto ->
            assertEquals("iOS", dto.platform)
        }
        echo(
            headers = mapOf(ClientContext.HEADER_PLATFORM to "IOS"),
        ) { dto ->
            assertEquals("iOS", dto.platform)
        }
    }

    @Test
    fun parsesAndroidPlatform_caseInsensitively() = runTest {
        echo(
            headers = mapOf(ClientContext.HEADER_PLATFORM to "android"),
        ) { dto ->
            assertEquals("Android", dto.platform)
        }
        echo(
            headers = mapOf(ClientContext.HEADER_PLATFORM to "Android"),
        ) { dto ->
            assertEquals("Android", dto.platform)
        }
    }

    @Test
    fun missingPlatformHeader_fallsBackToOther() = runTest {
        echo(headers = emptyMap()) { dto ->
            assertEquals("Other", dto.platform)
        }
    }

    @Test
    fun unknownPlatformValue_fallsBackToOther() = runTest {
        // Documented default — a future "web" / "windows" client that
        // hasn't been added to the parser still gets a working response,
        // just from the platform-agnostic catalog subset.
        echo(
            headers = mapOf(ClientContext.HEADER_PLATFORM to "web"),
        ) { dto ->
            assertEquals("Other", dto.platform)
        }
    }

    @Test
    fun appVersionAndBuildNumber_passThroughWhenPresent() = runTest {
        echo(
            headers = mapOf(
                ClientContext.HEADER_APP_VERSION to "1.4.2",
                ClientContext.HEADER_BUILD_NUMBER to "127",
            ),
        ) { dto ->
            assertEquals("1.4.2", dto.appVersion)
            assertEquals(127, dto.buildNumber)
        }
    }

    @Test
    fun appVersionAndBuildNumber_areNullWhenMissing() = runTest {
        echo(headers = emptyMap()) { dto ->
            assertNull(dto.appVersion)
            assertNull(dto.buildNumber)
        }
    }

    @Test
    fun buildNumber_isNullWhenNotAnInteger() = runTest {
        // toIntOrNull() — a future client that ships a non-numeric build
        // string (a hash, "dev", etc.) should degrade to null rather than
        // 500 the endpoint.
        echo(
            headers = mapOf(ClientContext.HEADER_BUILD_NUMBER to "abc"),
        ) { dto ->
            assertNull(dto.buildNumber)
        }
    }

    @Test
    fun countryCode_uppercasedWhenLengthIsTwo() = runTest {
        echo(
            headers = mapOf(ClientContext.HEADER_COUNTRY_CODE to "us"),
        ) { dto ->
            assertEquals("US", dto.countryCode)
        }
    }

    @Test
    fun countryCode_rejectedWhenLengthIsNotTwo() = runTest {
        // ISO 3166-1 alpha-2 is exactly two chars. Anything else is
        // either a tampering attempt or a stale client convention —
        // either way, drop to null so downstream code never branches on
        // a malformed code.
        echo(
            headers = mapOf(ClientContext.HEADER_COUNTRY_CODE to "USA"),
        ) { dto ->
            assertNull(dto.countryCode)
        }
        echo(
            headers = mapOf(ClientContext.HEADER_COUNTRY_CODE to "u"),
        ) { dto ->
            assertNull(dto.countryCode)
        }
        echo(
            headers = mapOf(ClientContext.HEADER_COUNTRY_CODE to ""),
        ) { dto ->
            assertNull(dto.countryCode)
        }
    }

    @Test
    fun countryCode_isNullWhenMissing() = runTest {
        echo(headers = emptyMap()) { dto ->
            assertNull(dto.countryCode)
        }
    }

    @Test
    fun preferredLocales_fallsBackToEnglish_whenAcceptLanguageMissing() = runTest {
        echo(headers = emptyMap()) { dto ->
            assertEquals(listOf("en"), dto.preferredLocales)
        }
    }

    @Test
    fun preferredLocales_passesThroughSingleLocale() = runTest {
        echo(
            headers = mapOf("Accept-Language" to "es-MX"),
        ) { dto ->
            assertEquals(listOf("es-MX"), dto.preferredLocales)
        }
    }

    @Test
    fun preferredLocales_areOrderedByQValueDescending() = runTest {
        // ktor's acceptLanguageItems() parses q-values and returns them
        // already sorted by quality descending. Pin the contract so a
        // future swap to a different parser maintains the ordering the
        // locale matcher depends on.
        echo(
            headers = mapOf("Accept-Language" to "fr;q=0.5, en;q=0.9, es;q=0.7"),
        ) { dto ->
            assertEquals(listOf("en", "es", "fr"), dto.preferredLocales)
        }
    }

    @Test
    fun preferredLocales_assumeQOneWhenUnspecified() = runTest {
        // Per RFC 7231: missing q-value implies q=1. So unweighted
        // entries should rank above explicitly-weighted lower entries.
        echo(
            headers = mapOf("Accept-Language" to "en, fr;q=0.5"),
        ) { dto ->
            // "en" is q=1 by default, so it should land first.
            assertTrue(dto.preferredLocales.isNotEmpty())
            assertEquals("en", dto.preferredLocales.first())
        }
    }

    private suspend fun echo(
        headers: Map<String, String>,
        assertions: (EchoDto) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                routing {
                    get("/echo-context") {
                        val ctx = call.clientContext()
                        call.respond(
                            EchoDto(
                                platform = ctx.platform.name,
                                appVersion = ctx.appVersion,
                                buildNumber = ctx.buildNumber,
                                preferredLocales = ctx.preferredLocales,
                                countryCode = ctx.countryCode,
                            ),
                        )
                    }
                }
            }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
            val resp = client.get("/echo-context") {
                headers.forEach { (name, value) -> header(name, value) }
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val dto: EchoDto = resp.body()
            assertions(dto)
        }
    }
}
