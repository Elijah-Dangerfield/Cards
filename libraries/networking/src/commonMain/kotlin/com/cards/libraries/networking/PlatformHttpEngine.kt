package com.dangerfield.cards.libraries.networking

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * The platform HTTP engine, chosen explicitly rather than by Ktor's runtime
 * auto-resolution. Every first-party [io.ktor.client.HttpClient] on the iOS
 * path must be built with this factory (`HttpClient(platformHttpEngineFactory)`)
 * so it always binds Darwin (NSURLSession) — the only Kotlin/Native engine with
 * TLS. Leaving the engine to auto-resolution let a TLS-incapable native engine
 * be picked for an HTTPS/WSS flush, which aborts the iOS process with
 * "TLS sessions are not supported on Native platform" (ENG-28 / CARDS-94).
 * Android binds OkHttp.
 */
expect val platformHttpEngineFactory: HttpClientEngineFactory<*>
