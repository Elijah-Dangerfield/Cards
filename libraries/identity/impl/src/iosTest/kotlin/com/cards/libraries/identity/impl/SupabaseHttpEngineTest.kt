package com.dangerfield.cards.libraries.identity.impl

import com.dangerfield.cards.libraries.networking.platformHttpEngineFactory
import io.ktor.client.engine.darwin.Darwin
import kotlin.test.Test
import kotlin.test.assertSame

class SupabaseHttpEngineTest {

    @Test
    fun `supabase client is fed the Darwin engine so signup never hits the TLS-incapable native engine`() {
        assertSame(Darwin, platformHttpEngineFactory)
    }
}
