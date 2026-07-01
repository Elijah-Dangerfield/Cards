package com.dangerfield.cards.libraries.networking.impl

import dev.skymansandy.wiretap.helper.launcher.launchWiretapConsole
import dev.skymansandy.wiretap.plugin.http.WiretapKtorHttpPlugin
import dev.skymansandy.wiretap.plugin.ws.WiretapKtorWebSocketPlugin
import io.ktor.client.HttpClientConfig

// The library's default is fine on Android: the console is a separate
// Activity whose root back maps to finish(), so the system back gesture /
// button returns to the app.
internal actual fun launchNetworkInspector() = launchWiretapConsole()

internal actual fun HttpClientConfig<*>.installNetworkInspector() {
    // Wiretap's DI is bootstrapped by an androidx.startup initializer that
    // doesn't run in host-JVM unit tests, so its request path would crash
    // there. Only install in a real app process. See the expect doc.
    if (isRunningUnitTest) return
    install(WiretapKtorHttpPlugin)
}

internal actual fun HttpClientConfig<*>.installWebSocketInspector() {
    // Same host-JVM-unit-test guard as the HTTP plugin: the WS plugin's frame
    // logging resolves Wiretap's DI, which isn't bootstrapped in unit tests.
    if (isRunningUnitTest) return
    install(WiretapKtorWebSocketPlugin) { enabled = true }
}

// True when running on the host JVM under a unit test. The JUnit runtime is on
// the test classpath but is never packaged into the shipped app, so its
// presence cleanly distinguishes "unit test" from "real device/app process".
private val isRunningUnitTest: Boolean by lazy {
    classExists("org.junit.Test") || classExists("org.junit.jupiter.api.Test")
}

private fun classExists(name: String): Boolean =
    try {
        Class.forName(name)
        true
    } catch (_: Throwable) {
        false
    }
