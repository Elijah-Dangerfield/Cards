package com.dangerfield.cards.libraries.cards

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.IntConfigValue
import com.dangerfield.cards.libraries.config.QaConfigValue
import com.dangerfield.cards.libraries.config.StringConfigValue
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The newest release the pipeline has shipped, written by `release.yml` right
 * after the Play upload (`PUT /v1/admin/config/flags/upgrade.latestVersion*`).
 *
 * These carry the version *name* the update prompt needs and nothing more.
 * They are deliberately not a "prompt everyone" switch: on their own they say
 * what we shipped, not what a given user can install yet, so
 * [AppUpdateSource] only trusts them once the store has confirmed an update
 * with the matching [LatestReleaseVersionCode]. They share the `upgrade.`
 * prefix with `MinSupportedVersionCode` so the QA menu groups them together.
 *
 * `0` / `""` are the unset sentinels, meaning "the pipeline has never
 * published a release". The prompt then stays silent.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class LatestReleaseVersionCode(appConfigMap: AppConfigMap) : IntConfigValue(appConfigMap) {
    override val name = "Latest release version code"
    override val path = "upgrade.latestVersionCode"
    override val default = 0
}

/** The `major.minor.patch` name of the build [LatestReleaseVersionCode] refers to. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = QaConfigValue::class, multibinding = true)
class LatestReleaseVersionName(appConfigMap: AppConfigMap) : StringConfigValue(appConfigMap) {
    override val name = "Latest release version name"
    override val path = "upgrade.latestVersionName"
    override val default = ""
}
