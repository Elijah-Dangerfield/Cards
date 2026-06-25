package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEventBus
import com.dangerfield.cards.libraries.cards.AppEvents
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@ContributesTo(AppScope::class)
interface CardsEventsDiModule {

    @Provides
    @SingleIn(AppScope::class)
    fun provideAppEvents(bus: AppEventBus): AppEvents = AppEvents(bus)
}
