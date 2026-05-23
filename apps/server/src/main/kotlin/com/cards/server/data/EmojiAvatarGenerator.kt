package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AvatarGenerator
import com.dangerfield.cards.server.domain.AvatarPalette
import com.dangerfield.cards.server.domain.AvatarStarterPack
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.random.Random

/**
 * Picks an emoji uniformly at random from [AvatarStarterPack]. The pack is
 * the single source of truth for both first-contact assignment and
 * profile-update validation, so the same list is used here.
 *
 * Random source: [Random.Default] (java.util.SplittableRandom under the
 * hood on JVM). Cryptographic randomness is unnecessary — adversaries
 * can't usefully predict the next assigned emoji.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class EmojiAvatarGenerator(
    private val random: Random = Random.Default,
) : AvatarGenerator {

    override fun random(): String = AvatarStarterPack.values.random(random)

    override fun randomBackgroundColor(): String = AvatarPalette.values.random(random)
}
