package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.LifetimeStatsRepository
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class LifetimeStatsRepositoryImpl(
    private val networkClient: NetworkClient,
) : LifetimeStatsRepository {

    override suspend fun fetchDistinctOpponents(): Result<Long> =
        networkClient.authedCall("me.stats", retry = RetryPolicy.idempotent()) { client ->
            client.get("/v1/me/stats").body<MeStatsDto>().distinctOpponentsPlayed
        }
}

@Serializable
private data class MeStatsDto(
    val distinctOpponentsPlayed: Long,
)
