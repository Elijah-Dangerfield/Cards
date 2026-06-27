package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.failure
import com.dangerfield.cards.libraries.navigation.ShareLauncher
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JvmShareLauncher @Inject constructor() : ShareLauncher {

    override fun share(text: String): Catching<Unit> = failure(
        UnsupportedOperationException("Sharing text is not supported on JVM target")
    )
}
