package com.dangerfield.cards.libraries.config.impl

import com.dangerfield.cards.libraries.config.AppConfigFlow
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.AppConfigRepository
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@ContributesTo(AppScope::class)
interface ConfigDiModule {

    @Provides
    @SingleIn(AppScope::class)
    fun provideConfigJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppConfigMap(repository: AppConfigRepository): AppConfigMap = repository.config()

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppConfigFlow(repository: AppConfigRepository): AppConfigFlow = AppConfigFlow(repository)
}
