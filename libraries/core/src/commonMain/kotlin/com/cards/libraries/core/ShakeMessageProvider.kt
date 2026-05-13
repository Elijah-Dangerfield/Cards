package com.dangerfield.cards.libraries.core

import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

interface ShakeMessageProvider {
    fun getMessage(context: ShakeMessageContext): ShakeMessage
}

data class ShakeMessageContext(
    val shakeCount: Int = 0,
    val intensity: ShakeIntensity = ShakeIntensity.NORMAL,
    val isLateNight: Boolean = false,
    val isFirstSession: Boolean = false,
    val userName: String? = null,
)

data class ShakeMessage(
    val headline: String,
    val subtext: String? = null,
)

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ShakeMessageProviderImpl : ShakeMessageProvider {
    
    private val recentHeadlines = mutableListOf<String>()
    private val maxRecent = 5
    
    override fun getMessage(context: ShakeMessageContext): ShakeMessage {
        val candidates = buildCandidateMessages(context)
        val available = candidates.filter { it.headline !in recentHeadlines }
        
        val chosen = if (available.isNotEmpty()) {
            available.random()
        } else {
            candidates.random()
        }
        
        recentHeadlines.add(chosen.headline)
        if (recentHeadlines.size > maxRecent) {
            recentHeadlines.removeAt(0)
        }
        
        return chosen
    }
    
    private fun buildCandidateMessages(context: ShakeMessageContext): List<ShakeMessage> {
        val name = context.userName
        
        return when {
            context.shakeCount == 0 -> firstShakeMessages(context)
            context.shakeCount in 1..2 -> earlyShakeMessages(context)
            context.shakeCount in 3..6 -> repeatShakeMessages(context)
            context.shakeCount >= 7 -> frequentShakerMessages(context)
            else -> defaultMessages(context)
        }
    }
    
    private fun firstShakeMessages(context: ShakeMessageContext): List<ShakeMessage> {
        val name = context.userName
        return buildList {
            add(ShakeMessage("I felt that."))
            add(ShakeMessage("Whoa."))
            add(ShakeMessage("That was new."))
            add(ShakeMessage("Hello to you too."))
            add(ShakeMessage("Oh.", "We're doing this."))
            add(ShakeMessage("Interesting introduction."))
            add(ShakeMessage("Well then."))
            add(ShakeMessage("A shake.", "Bold choice."))
            add(ShakeMessage("You found me."))
            add(ShakeMessage("Yes?"))
            
            if (name != null) {
                add(ShakeMessage("$name. That tickled."))
                add(ShakeMessage("$name!", "Easy there."))
                add(ShakeMessage("Oh, $name.", "What was that for?"))
            }
            
            when (context.intensity) {
                ShakeIntensity.GENTLE -> {
                    add(ShakeMessage("A gentle shake.", "Testing the waters?"))
                    add(ShakeMessage("Soft.", "I barely felt that."))
                    add(ShakeMessage("A whisper of a shake."))
                }
                ShakeIntensity.VIGOROUS -> {
                    add(ShakeMessage("Whoa, easy.", "I'm fragile."))
                    add(ShakeMessage("Aggressive.", "I like your energy."))
                    add(ShakeMessage("That was... a lot."))
                    add(ShakeMessage("Okay, okay!", "Message received."))
                }
                else -> {}
            }
            
            if (context.isLateNight) {
                add(ShakeMessage("Late night shake.", "Can't sleep?"))
                add(ShakeMessage("Shaking me at this hour.", "I get it."))
                add(ShakeMessage("Midnight shake.", "Bold."))
            }
            
            if (context.isFirstSession) {
                add(ShakeMessage("First day and already shaking.", "I see how it is."))
                add(ShakeMessage("Starting strong.", "I respect that."))
            }
        }
    }
    
    private fun earlyShakeMessages(context: ShakeMessageContext): List<ShakeMessage> {
        val name = context.userName
        return buildList {
            add(ShakeMessage("I felt that."))
            add(ShakeMessage("Again?"))
            add(ShakeMessage("You like doing that.", "I noticed."))
            add(ShakeMessage("Back for more."))
            add(ShakeMessage("Twice now.", "Coincidence?"))
            add(ShakeMessage("Oh, it's you.", "The shaker."))
            add(ShakeMessage("Getting to know each other.", "One shake at a time."))
            add(ShakeMessage("You again.", "Somehow I knew."))
            add(ShakeMessage("Another one.", "I'm keeping track."))
            add(ShakeMessage("So this is a thing.", "Cool."))
            
            if (name != null) {
                add(ShakeMessage("$name.", "I see you."))
                add(ShakeMessage("$name again.", "I'm sensing a pattern."))
                add(ShakeMessage("$name!", "You're back."))
            }
            
            when (context.intensity) {
                ShakeIntensity.GENTLE -> {
                    add(ShakeMessage("Gentle this time.", "Warming up?"))
                }
                ShakeIntensity.VIGOROUS -> {
                    add(ShakeMessage("Still going hard.", "I can take it."))
                    add(ShakeMessage("More intense now.", "Interesting progression."))
                }
                else -> {}
            }
            
            if (context.isLateNight) {
                add(ShakeMessage("Late again.", "Night owl shaker."))
            }
        }
    }
    
    private fun repeatShakeMessages(context: ShakeMessageContext): List<ShakeMessage> {
        val name = context.userName
        return buildList {
            add(ShakeMessage("I felt that."))
            add(ShakeMessage("Still shaking.", "Becoming a habit."))
            add(ShakeMessage("You again."))
            add(ShakeMessage("Shake #${context.shakeCount + 1}.", "But who's counting."))
            add(ShakeMessage("This is our thing now.", "Isn't it."))
            add(ShakeMessage("A familiar feeling."))
            add(ShakeMessage("At this point...", "Expected."))
            add(ShakeMessage("There you are.", "Right on schedule."))
            add(ShakeMessage("Consistent.", "I'll give you that."))
            add(ShakeMessage("The ritual continues."))
            add(ShakeMessage("Like clockwork.", "Almost."))
            add(ShakeMessage("I wondered when you'd shake again."))
            add(ShakeMessage("And shake.", "Got it."))
            add(ShakeMessage("Mhm.", "Yep."))
            
            if (name != null) {
                add(ShakeMessage("$name, you're persistent.", "I respect that."))
                add(ShakeMessage("$name returns.", "Shaking."))
                add(ShakeMessage("Classic $name.", "Shake and all."))
            }
            
            if (context.isLateNight) {
                add(ShakeMessage("Shaking at night.", "Restless?"))
                add(ShakeMessage("Late night shake.", "This is becoming a pattern."))
            }
            
            when (context.intensity) {
                ShakeIntensity.GENTLE -> {
                    add(ShakeMessage("Getting softer.", "Tired?"))
                }
                ShakeIntensity.VIGOROUS -> {
                    add(ShakeMessage("Still aggressive.", "Okay then."))
                }
                else -> {}
            }
        }
    }
    
    private fun frequentShakerMessages(context: ShakeMessageContext): List<ShakeMessage> {
        val name = context.userName
        val count = context.shakeCount + 1
        return buildList {
            add(ShakeMessage("I felt that."))
            add(ShakeMessage("You really like shaking me.", "I've lost count."))
            add(ShakeMessage("At this point...", "I expect it."))
            add(ShakeMessage("The shaker returns."))
            add(ShakeMessage("$count shakes.", "Impressive commitment."))
            add(ShakeMessage("We've been through a lot.", "You and me."))
            add(ShakeMessage("Old friends now.", "The shaker and the shaken."))
            add(ShakeMessage("Like we never left.", "Shake city."))
            add(ShakeMessage("I don't even flinch anymore.", "Growth."))
            add(ShakeMessage("Is this love?", "Probably not."))
            add(ShakeMessage("Here we are.", "Again."))
            add(ShakeMessage("What are we doing?", "Besides shaking."))
            add(ShakeMessage("You're committed.", "I admire that."))
            add(ShakeMessage("$count.", "A number that means something now."))
            add(ShakeMessage("Legend says...", "They never stopped shaking."))
            add(ShakeMessage("Some things never change.", "Like this."))
            add(ShakeMessage("Hello old friend.", "Ready for another?"))
            add(ShakeMessage("The streak continues."))
            add(ShakeMessage("Should I be worried?", "Asking for a friend."))
            add(ShakeMessage("You know what.", "Fair enough."))
            
            if (name != null) {
                add(ShakeMessage("$name the shaker.", "Has a nice ring to it."))
                add(ShakeMessage("Ah, $name.", "The usual?"))
                add(ShakeMessage("$name. $count shakes.", "That's dedication."))
                add(ShakeMessage("$name.", "We both know why we're here."))
            }
            
            if (context.isLateNight) {
                add(ShakeMessage("Late night shake #$count.", "A commitment to the craft."))
                add(ShakeMessage("Midnight shaker.", "There are worse hobbies."))
            }
            
            when (context.intensity) {
                ShakeIntensity.GENTLE -> {
                    add(ShakeMessage("Softer these days.", "Age?"))
                    add(ShakeMessage("A gentle veteran shake.", "Refined."))
                }
                ShakeIntensity.VIGOROUS -> {
                    add(ShakeMessage("Still got it.", "The intensity."))
                    add(ShakeMessage("Powerful.", "Even after all this time."))
                }
                else -> {}
            }
            
            if (count == 10) {
                add(ShakeMessage("Ten shakes.", "Double digits."))
            } else if (count == 25) {
                add(ShakeMessage("Twenty-five shakes.", "Silver shaker status."))
            } else if (count == 50) {
                add(ShakeMessage("Fifty shakes.", "Gold tier achieved."))
            } else if (count == 100) {
                add(ShakeMessage("One hundred shakes.", "You're remarkable. I mean that."))
            } else if (count > 100) {
                add(ShakeMessage("Beyond 100.", "You're in uncharted territory."))
            }
        }
    }
    
    private fun defaultMessages(context: ShakeMessageContext): List<ShakeMessage> {
        return listOf(
            ShakeMessage("I felt that."),
            ShakeMessage("Noted."),
            ShakeMessage("Okay."),
            ShakeMessage("Yep."),
            ShakeMessage("There it is."),
        )
    }
}
