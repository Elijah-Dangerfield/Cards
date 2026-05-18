package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.PlatformStore
import com.dangerfield.cards.server.domain.Product
import com.dangerfield.cards.server.domain.ProductCatalog
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.http.ClientContext
import com.dangerfield.cards.server.plugins.installSerialization
import com.dangerfield.cards.server.plugins.installStatusPages
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for `GET /v1/products`. Spins up the route + serialization
 * + status-pages plugins in a Ktor [testApplication] and hits the endpoint
 * with crafted headers.
 *
 * Verifies the parts of the API surface that clients depend on:
 *  - Localization picks the right strings.
 *  - Platform filtering surfaces only the caller's SKU.
 *  - Headers are parsed into a [ClientContext] the source can act on.
 *  - Cache-Control is set so the CDN doesn't have to guess.
 *  - The default-platform branch is sane for unknown clients.
 *
 * Tests use a fixed [FakeCatalogSource] so assertions are deterministic and
 * the in-memory production source can grow without breaking the contract
 * tests below.
 */
class ProductsRoutesTest {

    @Test
    fun returnsLocalizedTitle_forSpanishClient() = runTest {
        configureAndCall(
            language = "es-MX,es;q=0.9",
            platform = "android",
        ) { call ->
            assertEquals(HttpStatusCode.OK, call.status)
            val body = call.body<ProductCatalogResponse>()
            assertEquals(1, body.chipPacks.size)
            assertEquals("Pila de bolsillo", body.chipPacks.first().title)
        }
    }

    @Test
    fun fallsBackToEnglish_forUnsupportedLocale() = runTest {
        configureAndCall(
            language = "ja-JP",
            platform = "android",
        ) { call ->
            val body = call.body<ProductCatalogResponse>()
            assertEquals("Pocket Stack", body.chipPacks.first().title)
        }
    }

    @Test
    fun surfacesIosSku_forIosClient() = runTest {
        configureAndCall(language = "en", platform = "ios") { call ->
            val body = call.body<ProductCatalogResponse>()
            assertEquals("com.cards.iap.chips.small", body.chipPacks.first().store.sku)
        }
    }

    @Test
    fun surfacesAndroidSku_forAndroidClient() = runTest {
        configureAndCall(language = "en", platform = "android") { call ->
            val body = call.body<ProductCatalogResponse>()
            assertEquals("chips_small", body.chipPacks.first().store.sku)
        }
    }

    @Test
    fun unknownPlatform_doesNotError_andFallsBackToAndroid() = runTest {
        // No X-Platform header at all (the web preview / curl case).
        configureAndCall(language = "en", platform = null) { call ->
            assertEquals(HttpStatusCode.OK, call.status)
            val body = call.body<ProductCatalogResponse>()
            assertEquals("chips_small", body.chipPacks.first().store.sku)
        }
    }

    @Test
    fun setsCacheControlMaxAge() = runTest {
        configureAndCall(language = "en", platform = "android") { call ->
            val cacheControl = call.headers[HttpHeaders.CacheControl]
            assertNotNull(cacheControl, "Cache-Control header set")
            assertTrue(
                cacheControl.contains("max-age=300"),
                "max-age=300 present in: $cacheControl",
            )
        }
    }

    @Test
    fun platformFilter_excludesProductNotSoldOnCallerPlatform() = runTest {
        // The fake catalog includes one Android-only product; an iOS client
        // should not see it.
        configureAndCall(
            language = "en",
            platform = "ios",
            source = FakeCatalogSource(includeAndroidOnlyExtra = true),
        ) { call ->
            val body = call.body<ProductCatalogResponse>()
            assertEquals(1, body.chipPacks.size, "iOS client sees only the cross-platform pack")
            assertTrue(body.chipPacks.none { it.id == "android_only_pack" })
        }
        configureAndCall(
            language = "en",
            platform = "android",
            source = FakeCatalogSource(includeAndroidOnlyExtra = true),
        ) { call ->
            val body = call.body<ProductCatalogResponse>()
            assertEquals(2, body.chipPacks.size, "Android client sees both")
        }
    }

    @Test
    fun nullBadge_serializesAsNull_notEmptyString() = runTest {
        configureAndCall(language = "en", platform = "android") { call ->
            val body = call.body<ProductCatalogResponse>()
            // The fake source's first pack has no badge; the wire format
            // should carry `null` (not `""`) so the client renders no chip.
            assertNull(body.chipPacks.first().badge)
        }
    }

    @Test
    fun parsesAppVersionAndBuildNumberHeaders() = runTest {
        // Catalog source can vary by app version; we verify the headers
        // reach the source by capturing the received context.
        val source = FakeCatalogSource()
        configureAndCall(
            language = "en",
            platform = "android",
            appVersion = "2.5.1",
            buildNumber = "42",
            source = source,
        ) { call ->
            assertEquals(HttpStatusCode.OK, call.status)
            val received = source.lastContext
            assertNotNull(received)
            assertEquals("2.5.1", received.appVersion)
            assertEquals(42, received.buildNumber)
        }
    }

    @Test
    fun parsesCountryHeader_normalizesToUppercase() = runTest {
        val source = FakeCatalogSource()
        configureAndCall(
            language = "en",
            platform = "android",
            country = "us",  // lowercase
            source = source,
        ) { call ->
            assertEquals(HttpStatusCode.OK, call.status)
            assertEquals("US", source.lastContext?.countryCode)
        }
    }

    @Test
    fun missingCountryHeader_yieldsNullCountry() = runTest {
        val source = FakeCatalogSource()
        configureAndCall(
            language = "en",
            platform = "android",
            country = null,
            source = source,
        ) { call ->
            assertEquals(HttpStatusCode.OK, call.status)
            assertNull(source.lastContext?.countryCode)
        }
    }

    @Test
    fun invalidCountryHeader_isDropped_notForwarded() = runTest {
        // ISO 3166 is exactly 2 letters — junk values shouldn't pass through.
        val source = FakeCatalogSource()
        configureAndCall(
            language = "en",
            platform = "android",
            country = "USA",  // 3 letters, invalid
            source = source,
        ) { call ->
            assertNull(source.lastContext?.countryCode)
        }
    }

    // ---------- Test scaffolding ----------

    /**
     * Spins up a minimal Ktor app with just our route + the plugins needed to
     * round-trip JSON, hits the endpoint with the given headers, and hands
     * the response to [assert].
     */
    private suspend fun configureAndCall(
        language: String,
        platform: String?,
        appVersion: String? = "1.0.0",
        buildNumber: String? = "1",
        country: String? = null,
        source: ProductCatalogSource = FakeCatalogSource(),
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) {
        testApplication {
            application {
                installSerialization()
                installStatusPages()
                routing {
                    productsRoutes(source)
                }
            }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
            val response = client.get("/v1/products") {
                header(HttpHeaders.AcceptLanguage, language)
                platform?.let { header(ClientContext.HEADER_PLATFORM, it) }
                appVersion?.let { header(ClientContext.HEADER_APP_VERSION, it) }
                buildNumber?.let { header(ClientContext.HEADER_BUILD_NUMBER, it) }
                country?.let { header(ClientContext.HEADER_COUNTRY_CODE, it) }
            }
            assert(response)
        }
    }

    /**
     * Deterministic catalog for tests, plus a spy field [lastContext] so
     * header-parsing tests can assert against what reached the source.
     */
    private class FakeCatalogSource(
        private val includeAndroidOnlyExtra: Boolean = false,
    ) : ProductCatalogSource {
        var lastContext: ClientContext? = null
            private set

        override suspend fun read(context: ClientContext): ProductCatalog {
            lastContext = context
            val base = Product.ChipPack(
                id = "chip_pack_small",
                titleByLocale = mapOf("en" to "Pocket Stack", "es" to "Pila de bolsillo"),
                subtitleByLocale = mapOf("en" to "5,000 chips"),
                iconEmoji = "🪙",
                grantsChips = 5_000,
                store = PlatformStore(
                    ios = PlatformStore.StoreSku("com.cards.iap.chips.small", "$0.99"),
                    android = PlatformStore.StoreSku("chips_small", "$0.99"),
                ),
            )
            val extras = if (includeAndroidOnlyExtra) {
                listOf(
                    Product.ChipPack(
                        id = "android_only_pack",
                        titleByLocale = mapOf("en" to "Android Special"),
                        subtitleByLocale = mapOf("en" to "1,000 chips"),
                        iconEmoji = "🪙",
                        platforms = setOf(ClientContext.Platform.Android),
                        grantsChips = 1_000,
                        store = PlatformStore(
                            ios = PlatformStore.StoreSku("noop", "$0.00"),
                            android = PlatformStore.StoreSku("android_special", "$0.49"),
                        ),
                    ),
                )
            } else {
                emptyList()
            }
            val filtered = (listOf(base) + extras).filter { context.platform in it.platforms }
            return ProductCatalog(chipPacks = filtered, chipOffers = emptyList())
        }
    }
}
