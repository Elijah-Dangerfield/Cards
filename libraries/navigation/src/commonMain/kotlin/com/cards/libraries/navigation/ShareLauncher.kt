package com.dangerfield.cards.libraries.navigation

import com.dangerfield.cards.libraries.core.Catching

fun interface ShareLauncher {
    fun share(text: String): Catching<Unit>
}
