package com.dangerfield.cards.libraries.networking.impl

import dev.skymansandy.wiretap.helper.launcher.launchWiretapConsole

// The library's default is fine on Android: the console is a separate
// Activity whose root back maps to finish(), so the system back gesture /
// button returns to the app.
internal actual fun launchNetworkInspector() = launchWiretapConsole()
