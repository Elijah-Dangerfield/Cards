package com.dangerfield.cards.libraries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration.Companion.Underline
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.core.throwIfDebug
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD800
import androidx.compose.ui.tooling.preview.Preview

const val ANNOTATED_STRING_URL_KEY = "URL"
const val ANNOTATED_STRING_ON_CLICK_KEY = "ON_CLICK"

data class ClickableTextValue internal constructor(
    val text: AnnotatedString,
    internal val clickHandlers: Map<String, ClickableTextSegment> = emptyMap()
)

internal data class ClickableTextSegment(
    val onClick: () -> Unit,
    val bounceOnPress: Boolean,
    val bounceScale: Float
)

data class LinkAppearance(
    val style: SpanStyle,
    val bounceOnPress: Boolean = true,
    val bounceScale: Float = ClickableTextDefaults.DefaultBounceScale
)

private fun LinkAppearance.sanitizedBounceScale(): Float = when {
    !bounceOnPress -> 1f
    bounceScale <= 0f -> ClickableTextDefaults.DefaultBounceScale
    bounceScale > 1f -> 1f
    else -> bounceScale
}

object ClickableTextDefaults {
    const val DefaultBounceScale = 0.94f
    const val GentleBounceScale = 0.98f

    @Composable
    @ReadOnlyComposable
    fun primaryLinkAppearance(): LinkAppearance = LinkAppearance(
        style = primaryLinkStyle()
    )

    @Composable
    @ReadOnlyComposable
    fun subtleLinkAppearance(): LinkAppearance = LinkAppearance(
        style = subtleLinkStyle(),
        bounceScale = GentleBounceScale
    )

    @Composable
    @ReadOnlyComposable
    fun dangerLinkAppearance(): LinkAppearance = LinkAppearance(
        style = dangerLinkStyle()
    )

    @Composable
    @ReadOnlyComposable
    fun urlLinkAppearance(): LinkAppearance = LinkAppearance(
        style = primaryLinkStyle(),
        bounceOnPress = false,
        bounceScale = 1f
    )

    @Composable
    @ReadOnlyComposable
    fun primaryLinkStyle(): SpanStyle = SpanStyle(
        color = AppTheme.colors.content.color,
        fontWeight = FontWeight.SemiBold,
        textDecoration = Underline
    )

    @Composable
    @ReadOnlyComposable
    fun subtleLinkStyle(): SpanStyle = SpanStyle(
        color = AppTheme.colors.contentSecondary.color,
        fontWeight = FontWeight.Medium,
        textDecoration = Underline
    )

    @Composable
    @ReadOnlyComposable
    fun dangerLinkStyle(): SpanStyle = SpanStyle(
        color = AppTheme.colors.danger.color,
        fontWeight = FontWeight.SemiBold,
        textDecoration = Underline
    )
}

fun String.toClickableTextValue(): ClickableTextValue = AnnotatedString(this).toClickableTextValue()

fun AnnotatedString.toClickableTextValue(): ClickableTextValue = ClickableTextValue(this)

@Composable
fun buildClickableText(
    text: String,
    builder: ClickableTextBuilder.() -> Unit = {}
): ClickableTextValue = AnnotatedString(text).buildClickableText(builder)

@Composable
fun AnnotatedString.buildClickableText(
    builder: ClickableTextBuilder.() -> Unit = {}
): ClickableTextValue {
    val palette = LinkAppearancePalette(
        primary = ClickableTextDefaults.primaryLinkAppearance(),
        subtle = ClickableTextDefaults.subtleLinkAppearance(),
        danger = ClickableTextDefaults.dangerLinkAppearance()
    )

    return ClickableTextBuilder(this, palette).apply(builder).build()
}

class ClickableTextBuilder internal constructor(
    initialText: AnnotatedString,
    val palette: LinkAppearancePalette
) {
    private var workingText = initialText
    private val handlers = mutableMapOf<String, ClickableTextSegment>()
    private var annotationCount = 0

    fun link(
        linkText: String,
        appearance: LinkAppearance = palette.primary,
        onClick: () -> Unit
    ) {
        val startIndex = workingText.text.indexOf(linkText)
        if (startIndex < 0) {
            throwIfDebug(IllegalArgumentException("String ${workingText.text} does not contain the specific text: $linkText"))
            return
        }

        val range = startIndex until startIndex + linkText.length
        registerLink(range, appearance, onClick)
    }

    fun link(
        range: IntRange,
        appearance: LinkAppearance = palette.primary,
        onClick: () -> Unit
    ) {
        if (range.first < 0 || range.last >= workingText.text.length || range.isEmpty()) {
            throwIfDebug(IllegalArgumentException("Invalid range $range for text of length ${workingText.text.length}"))
            return
        }

        registerLink(range, appearance, onClick)
    }

    fun subtleLink(
        linkText: String,
        onClick: () -> Unit
    ) = link(linkText, palette.subtle, onClick)

    fun subtleLink(
        range: IntRange,
        onClick: () -> Unit
    ) = link(range, palette.subtle, onClick)

    fun dangerLink(
        linkText: String,
        onClick: () -> Unit
    ) = link(linkText, palette.danger, onClick)

    fun dangerLink(
        range: IntRange,
        onClick: () -> Unit
    ) = link(range, palette.danger, onClick)

    private fun registerLink(
        range: IntRange,
        appearance: LinkAppearance,
        onClick: () -> Unit
    ) {
        val annotationValue = "click-${annotationCount++}"
        workingText = AnnotatedString.Builder(workingText).apply {
            makeLookClickable(
                linkRange = range,
                annotation = ANNOTATED_STRING_ON_CLICK_KEY to annotationValue,
                style = appearance.style
            )
        }.toAnnotatedString()

        handlers[annotationValue] = ClickableTextSegment(
            onClick = onClick,
            bounceOnPress = appearance.bounceOnPress,
            bounceScale = appearance.sanitizedBounceScale()
        )
    }

    internal fun build(): ClickableTextValue = ClickableTextValue(workingText, handlers)
}

data class LinkAppearancePalette(
    val primary: LinkAppearance,
    val subtle: LinkAppearance,
    val danger: LinkAppearance
)

fun String.makeBold(boldString: String): AnnotatedString = buildAnnotatedString {
    val startIndex = this@makeBold.indexOf(boldString)
    val endIndex = startIndex + boldString.length

    append(this@makeBold)

    if (startIndex < 0) {
        throwIfDebug(IllegalArgumentException("String ${this@makeBold} does not contain the specific text: $boldString"))
        return@buildAnnotatedString
    }

    addStyle(
        style = SpanStyle(fontWeight = FontWeight.W700),
        start = startIndex,
        end = endIndex
    )
    toAnnotatedString()
}

fun AnnotatedString.underline(underlinedString: String): AnnotatedString = buildAnnotatedString {
    val startIndex = this@underline.indexOf(underlinedString)
    val endIndex = startIndex + underlinedString.length

    append(this@underline)

    if (startIndex < 0) {
        throwIfDebug(IllegalArgumentException("String ${this@underline} does not contain the specific text: $underlinedString"))
        return@buildAnnotatedString
    }

    addStyle(
        style = SpanStyle(textDecoration = Underline),
        start = startIndex,
        end = endIndex
    )
    toAnnotatedString()
}

fun String.underline(underlinedString: String): AnnotatedString = buildAnnotatedString {
    val startIndex = this@underline.indexOf(underlinedString)
    val endIndex = startIndex + underlinedString.length

    append(this@underline)

    if (startIndex < 0) {
        throwIfDebug(IllegalArgumentException("String ${this@underline} does not contain the specific text: $underlinedString"))
        return@buildAnnotatedString
    }

    addStyle(
        style = SpanStyle(textDecoration = Underline),
        start = startIndex,
        end = endIndex
    )
    toAnnotatedString()
}

fun String.addStyle(stringToStyle: String, style: SpanStyle): AnnotatedString {
    val startIndex = this.indexOf(stringToStyle)
    val endIndex = startIndex + stringToStyle.length

    if (startIndex < 0) {
        throwIfDebug(IllegalArgumentException("String $this does not contain the specific text: $stringToStyle"))
        return buildAnnotatedString {
            append(this@addStyle)
        }
    }

    return buildAnnotatedString {
        append(this@addStyle)
        addStyle(
            style = style,
            start = startIndex,
            end = endIndex
        )
    }
}

fun AnnotatedString.detectAndAnnotateLinks(
    style: SpanStyle,
    annotationTag: String = ANNOTATED_STRING_URL_KEY
): AnnotatedString {
    val regex = Regex("""\b(?:https?://|www\.)\S+\b""")
    val matches = regex.findAll(this)

    val annotatedString = buildAnnotatedString {
        append(this@detectAndAnnotateLinks)
        matches.forEach {
            this.makeLookClickable(
                linkRange = it.range,
                annotation = annotationTag to it.value,
                style = style
            )
        }
    }

    return annotatedString
}

fun AnnotatedString.Builder.makeLookClickable(
    linkRange: IntRange,
    annotation: Pair<String, String>? = null,
    style: SpanStyle
)  {
    val startIndex = linkRange.first
    val endIndex = linkRange.last + 1

    addStyle(
        style = style,
        start = startIndex,
        end = endIndex
    )

    if (annotation != null) {
        addStringAnnotation(
            tag = annotation.first,
            annotation = annotation.second,
            start = startIndex,
            end = endIndex
        )
    }
}

@Preview()
@Composable
private fun MakeLinkPreview() {
    PreviewContent {

        Column {
            val clickable = buildClickableText("This is some random text. But this text is clickable") {
                link("But this text is clickable") {}
            }

            Text(clickable.text)

            VerticalSpacerD800()

        }
    }
}

@Preview(heightDp = 900)
@Composable
private fun ClickableTextBuilderCatalogPreview() {
    PreviewContent {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            CatalogEntry(
                title = "Single primary link",
                body = buildClickableText("Need help? Contact support.") {
                    link("Contact support") {}
                }
            )

            CatalogEntry(
                title = "Mixed emphasis",
                body = buildClickableText("Review the FAQ or delete your account.") {
                    subtleLink("FAQ") {}
                    dangerLink("delete your account") {}
                }
            )

            CatalogEntry(
                title = "Range based link",
                body = buildClickableText("Tap anywhere in this sentence to resend verification.") {
                    val highlight = 0 until "Tap anywhere in this sentence".length
                    link(range = highlight) {}
                }
            )

            CatalogEntry(
                title = "Multiple inline links",
                body = buildClickableText("Terms, Privacy, and Code of Conduct") {
                    link("Terms") {}
                    link("Privacy") {}
                    link("Code of Conduct") {}
                }
            )
        }
    }
}

@Composable
private fun CatalogEntry(
    title: String,
    body: ClickableTextValue
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            typography = AppTheme.typography.Body.B700
        )
        Text(body.text)
    }
}
