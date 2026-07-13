package com.dangerfield.cards.libraries.networking

import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertSame

class PlatformHttpEngineTest {

    @Test
    fun `android binds the OkHttp engine`() {
        assertSame(OkHttp, platformHttpEngineFactory)
    }
}
