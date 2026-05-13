package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * A composable function that displays a text with a typewriter-like effect, revealing characters in chunks.
 *
 * @param text The input text to be displayed with the typewriter effect.
 * @param minDelayInMillis The minimum delay in milliseconds between revealing character chunks, defaults to 10ms.
 * @param maxDelayInMillis The maximum delay in milliseconds between revealing character chunks, defaults to 50ms.
 * @param minCharacterChunk The minimum number of characters to reveal at once, defaults to 1.
 * @param maxCharacterChunk The maximum number of characters to reveal at once, defaults to 5.
 * @param onEffectCompleted A callback function invoked when the entire text has been revealed.
 * @param displayTextComposable A composable function that receives the text to display with the typewriter effect.
 *
 * @throws IllegalArgumentException if [minDelayInMillis] is greater than [maxDelayInMillis].
 * @throws IllegalArgumentException if [minCharacterChunk] is greater than [maxCharacterChunk].
 */
@Composable
fun TypewriterTextEffect(
    text: String,
    minDelayInMillis: Long = 10,
    maxDelayInMillis: Long = 50,
    minCharacterChunk: Int = 1,
    maxCharacterChunk: Int = 5,
    onEffectCompleted: () -> Unit = {},
    displayTextComposable: @Composable (displayedText: String) -> Unit
) {
    // Ensure minDelayInMillis is less than or equal to maxDelayInMillis
    require(minDelayInMillis <= maxDelayInMillis) {
        "TypewriterTextEffect: Invalid delay range. minDelayInMillis ($minDelayInMillis) must be less than or equal to maxDelayInMillis ($maxDelayInMillis)."
    }

    // Ensure minCharacterChunk is less than or equal to maxCharacterChunk
    require(minCharacterChunk <= maxCharacterChunk) {
        "TypewriterTextEffect: Invalid character chunk range. minCharacterChunk ($minCharacterChunk) must be less than or equal to maxCharacterChunk ($maxCharacterChunk)."
    }

    if (LocalInspectionMode.current) {
        displayTextComposable(text)
        return
    }

    // Initialize and remember the displayedText
    var displayedText by remember { mutableStateOf("") }

    // Call the displayTextComposable with the current displayedText value
    displayTextComposable(displayedText)

    // Launch the effect to update the displayedText value over time
    LaunchedEffect(text) {
        val textLength = text.length
        var endIndex = 0

        while (endIndex < textLength) {
            endIndex = minOf(
                endIndex + Random.nextInt(minCharacterChunk, maxCharacterChunk + 1),
                textLength
            )
            displayedText = text.take(endIndex)
            delay(Random.nextLong(minDelayInMillis, maxDelayInMillis))
        }
        onEffectCompleted()
    }
}

/**
 * An example composable that uses the TypewriterTextEffect function.
 */
@Composable
fun ExampleTypewriterTextEffect() {
    // Use the TypewriterTextEffect composable with a Text composable to display the text with the typewriter effect
    Box(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TypewriterTextEffect(text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.") { displayedText ->
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = displayedText,
            )
        }
    }
}