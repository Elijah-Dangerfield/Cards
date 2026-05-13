package com.dangerfield.cards.libraries.config

import kotlinx.coroutines.flow.Flow

/**
 * Convenience wrapper that exposes [AppConfigRepository.configStream] as a directly injectable [Flow].
 * Use this when consumers only care about observing config updates reactively instead of depending
 * on the repository abstraction and its other APIs.
 */
class AppConfigFlow(
    private val repository: AppConfigRepository,
    private val backingFlow: Flow<AppConfigMap> = repository.configStream()
) : Flow<AppConfigMap> by backingFlow
