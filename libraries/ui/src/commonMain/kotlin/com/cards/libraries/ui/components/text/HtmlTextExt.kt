package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.dangerfield.cards.libraries.ui.components.text.removeUnknownTags
import kotlin.math.min
import kotlin.plus

/**
 * The tags to interpret. Add tags here and in [tagToStyle].
 */
private val knownTags = linkedMapOf(
    "<b>" to "</b>",
    "<i>" to "</i>",
    "<u>" to "</u>"
)

/**
 * Removes HTML tags and handles the ones that are supported.
 */
fun String.processHtmlTags(): AnnotatedString {
    val stringBuilder = removeUnknownTags()

    // Handle <br> tags
    val cleanedString = stringBuilder.toString().replace("<br>", "\n")

    // Build the AnnotatedString
    return buildAnnotatedString {
        com.dangerfield.cards.libraries.ui.components.text.recurse(cleanedString, this)
    }
}

private fun String.removeUnknownTags(): StringBuilder {
    val removeRanges = mutableListOf<IntRange>()
    val stringBuilder = StringBuilder()
    var lastEnd = 0

    // Find and collect all tags to be removed
    Regex("<[^>]*>").findAll(this).forEach {
        if (it.value !in com.dangerfield.cards.libraries.ui.components.text.knownTags.keys && it.value !in com.dangerfield.cards.libraries.ui.components.text.knownTags.values) {
            removeRanges.add(it.range)
        }
    }

    // Build the string without the unwcardsd tags
    removeRanges.forEach {
        stringBuilder.append(this.substring(lastEnd, it.first))
        lastEnd = it.last + 1
    }
    stringBuilder.append(
        this.substring(
            lastEnd,
            this.length
        )
    ) // append the rest of the string after the last tag
    return stringBuilder
}

/**
 * Recurses through the given HTML String to convert it to an AnnotatedString.
 *
 * @param string the String to examine.
 * @param to the AnnotatedString to append to.
 */
private fun recurse(string: String, to: AnnotatedString.Builder) {
    //Find the opening tag that the given String starts with, if any.
    val startTag = com.dangerfield.cards.libraries.ui.components.text.knownTags.keys.find { string.startsWith(it) }

    //Find the closing tag that the given String starts with, if any.
    val endTag = com.dangerfield.cards.libraries.ui.components.text.knownTags.values.find { string.startsWith(it) }

    when {
        //If the String starts with a closing tag, then pop the latest-applied
        //SpanStyle and continue recursing.
        com.dangerfield.cards.libraries.ui.components.text.knownTags.any { string.startsWith(it.value) } -> {
            to.pop()
            com.dangerfield.cards.libraries.ui.components.text.recurse(string.removeRange(0, endTag!!.length), to)
        }
        //If the String starts with an opening tag, apply the appropriate
        //SpanStyle and continue recursing.
        com.dangerfield.cards.libraries.ui.components.text.knownTags.any { string.startsWith(it.key) } -> {
            to.pushStyle(com.dangerfield.cards.libraries.ui.components.text.tagToStyle(startTag!!))
            com.dangerfield.cards.libraries.ui.components.text.recurse(string.removeRange(0, startTag.length), to)
        }
        //If the String doesn't start with an opening or closing tag, but does contain either,
        //find the lowest index (that isn't -1/not found) for either an opening or closing tag.
        //Append the text normally up until that lowest index, and then recurse starting from that index.
        com.dangerfield.cards.libraries.ui.components.text.knownTags.any { string.contains(it.key) || string.contains(it.value) } -> {
            val firstStart =
                com.dangerfield.cards.libraries.ui.components.text.knownTags.keys.map { string.indexOf(it) }.filterNot { it == -1 }.minOrNull() ?: -1
            val firstEnd =
                com.dangerfield.cards.libraries.ui.components.text.knownTags.values.map { string.indexOf(it) }.filterNot { it == -1 }.minOrNull()
                    ?: -1
            val first = when {
                firstStart == -1 -> firstEnd
                firstEnd == -1 -> firstStart
                else -> min(firstStart, firstEnd)
            }

            to.append(string.substring(0, first))

            com.dangerfield.cards.libraries.ui.components.text.recurse(string.removeRange(0, first), to)
        }
        //There weren't any supported tags found in the text. Just append it all normally.
        else -> {
            to.append(string)
        }
    }
}

/**
 * Get a [SpanStyle] for a given (opening) tag.
 * Add your own tag styling here by adding its opening tag to
 * the when clause and then instantiating the appropriate [SpanStyle].
 *
 * @return a [SpanStyle] for the given tag.
 */
private fun tagToStyle(tag: String): SpanStyle {
    return when (tag) {
        "<b>" -> {
            SpanStyle(fontWeight = FontWeight.Bold)
        }

        "<i>" -> {
            SpanStyle(fontStyle = FontStyle.Italic)
        }

        "<u>" -> {
            SpanStyle(textDecoration = TextDecoration.Underline)
        }
        //This should only throw if you add a tag to the [tags] Map and forget to add it
        //to this function.
        else -> throw IllegalArgumentException("Tag $tag is not valid.")
    }
}