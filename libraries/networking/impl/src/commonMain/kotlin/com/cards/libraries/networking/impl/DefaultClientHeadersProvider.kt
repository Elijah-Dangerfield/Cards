package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.networking.ClientHeaders
import com.dangerfield.cards.libraries.networking.ClientHeadersProvider
import com.dangerfield.cards.libraries.networking.InstallIdProvider
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default [ClientHeadersProvider] composed from [BuildInfo] (compile-time
 * constants — platform, app version, build number) and [LocaleSource] (runtime
 * OS state — current language list, country code).
 *
 * Build info is constant per process so we cache it. Locale changes on the fly
 * (user switches system language), so it's re-read on every call. The install
 * id is hydrated in the background by [InstallIdProvider]; calls before
 * hydration return `null` and the header is omitted (server treats absence
 * the same as "client too old to send one").
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultClientHeadersProvider(
    private val installIdProvider: InstallIdProvider,
) : ClientHeadersProvider {

    private val platform: String = when (BuildInfo.platform) {
        Platform.Android -> "android"
        Platform.iOS -> "ios"
    }
    private val appVersion: String = BuildInfo.versionName
    private val buildNumber: String = BuildInfo.buildNumber.toString()

    override fun current(): ClientHeaders = ClientHeaders(
        platform = platform,
        appVersion = appVersion,
        buildNumber = buildNumber,
        acceptLanguage = LocaleSource.acceptLanguage(),
        countryCode = LocaleSource.countryCode(),
        installId = installIdProvider.current(),
    )
}
