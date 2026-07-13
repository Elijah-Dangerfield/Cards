package com.dangerfield.cards.libraries.networking

import io.ktor.client.engine.darwin.Darwin
import kotlin.test.Test
import kotlin.test.assertSame

class PlatformHttpEngineTest {

    @Test
    fun `ios binds the Darwin engine so no TLS-incapable native engine can be auto-resolved`() {
        assertSame(Darwin, platformHttpEngineFactory)
    }
}
