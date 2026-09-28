package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppUpdateSource
import com.dangerfield.cards.libraries.cards.AppVersion
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import platform.Foundation.NSBundle
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

private const val LOOKUP_URL = "https://itunes.apple.com/lookup"
private const val LOOKUP_TIMEOUT_MS = 5_000L

/**
 * Apple's public iTunes lookup, keyed on the running bundle id so a TestFlight
 * or Xcode build asks about the same listing the store build does.
 *
 * A bare [HttpClient] rather than `NetworkClient`: that one is configured for
 * our own server (base URL, auth, client headers) and none of it belongs on a
 * request to Apple. The lookup only lags the store, never leads it, so a
 * version it reports is always installable.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ITunesAppUpdateSource(
    private val dispatcherProvider: DispatcherProvider,
) : AppUpdateSource {

    private val logger = KLog.withTag("ITunesAppUpdateSource")

    private val client by lazy {
        HttpClient(Darwin) {
            install(HttpTimeout) { requestTimeoutMillis = LOOKUP_TIMEOUT_MS }
        }
    }

    override suspend fun latestAvailableVersion(): AppVersion? = withContext(dispatcherProvider.io) {
        Catching {
            val bundleId = NSBundle.mainBundle.bundleIdentifier ?: return@Catching null
            val body = client.get(LOOKUP_URL) { parameter("bundleId", bundleId) }.bodyAsText()
            iTunesLookupVersion(body)
        }
            .onFailure { logger.d(it) { "iTunes lookup failed; not prompting." } }
            .getOrNull()
    }
}
