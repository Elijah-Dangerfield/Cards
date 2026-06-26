package com.dangerfield.cards.server.di

/**
 * DI scope for the lifetime of the server process. Parallel to [software.amazon.lastmile.kotlin.inject.anvil.AppScope]
 * on the client — same pattern, separate scope so client and server DI graphs can't accidentally
 * cross-pollinate.
 *
 * Use it on server-side services:
 *
 * ```
 * @SingleIn(ServerScope::class)
 * @ContributesBinding(ServerScope::class)
 * @Inject
 * class PostgresAppConfigSource(...) : AppConfigSource { ... }
 * ```
 */
abstract class ServerScope private constructor()
