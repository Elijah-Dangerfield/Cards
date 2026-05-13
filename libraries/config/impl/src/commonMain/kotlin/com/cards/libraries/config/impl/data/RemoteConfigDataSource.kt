package com.dangerfield.cards.libraries.config.impl.data

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.core.Catching

interface RemoteConfigDataSource {
    suspend fun getConfig(): Catching<AppConfigMap>
}
