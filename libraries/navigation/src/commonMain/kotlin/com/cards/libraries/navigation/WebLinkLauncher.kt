package com.dangerfield.cards.libraries.navigation

import com.dangerfield.cards.libraries.core.Catching

fun interface WebLinkLauncher {
    fun open(url: String): Catching<Unit>
}
