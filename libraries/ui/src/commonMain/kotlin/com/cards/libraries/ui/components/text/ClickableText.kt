package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextGeometricTransform
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.typography.TypographyResource
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.ANNOTATED_STRING_ON_CLICK_KEY
import com.dangerfield.cards.libraries.ui.ANNOTATED_STRING_URL_KEY
import com.dangerfield.cards.libraries.ui.ClickableTextDefaults
import com.dangerfield.cards.libraries.ui.ClickableTextSegment
import com.dangerfield.cards.libraries.ui.ClickableTextValue
import com.dangerfield.cards.libraries.ui.LinkAppearance
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.buildClickableText
import com.dangerfield.cards.libraries.ui.detectAndAnnotateLinks
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import org.jetbrains.compose.ui.tooling.preview.Preview


@NonRestartableComposable
@Composable
fun ClickableText(
    text: String,
    modifier: Modifier = Modifier,
    color: ColorResource? = null,
    lineBreak: LineBreak? = null,
    hyphens: Hyphens? = null,
    onClickAnnotatedText: (String) -> Unit = {},
    onClickUrl: (String) -> Unit = {},
    autoLinkUrls: Boolean = true,
    autoLinkAppearance: LinkAppearance = ClickableTextDefaults.urlLinkAppearance(),
    typography: TypographyResource = LocalTextConfig.current.typography
        ?: AppTheme.typography.Default,
    textDecoration: TextDecoration = LocalTextConfig.current.textDecoration ?: TextDecoration.None,
    textAlign: TextAlign? = LocalTextConfig.current.textAlign,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    overflow: TextOverflow = LocalTextConfig.current.overflow ?: DefaultTextOverflow,
    softWrap: Boolean = LocalTextConfig.current.softWrap ?: true,
    maxLines: Int = LocalTextConfig.current.maxLines ?: Int.MAX_VALUE,
) {
    val style = typography.toStyle(color, textDecoration, textAlign, hyphens, lineBreak)
    val autoLinkStyle = autoLinkAppearance.style
    val annotatedText = remember(text, autoLinkUrls, autoLinkStyle) {
        val processed = text.processHtmlTags()
        if (autoLinkUrls) processed.detectAndAnnotateLinks(autoLinkStyle) else processed
    }

    ClickableTextInternal(
        annotatedText = annotatedText,
        modifier = modifier,
        style = style,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        onClickAnnotatedText = onClickAnnotatedText,
        onClickUrl = onClickUrl,
        clickHandlers = emptyMap()
    )
}

@NonRestartableComposable
@Composable
fun ClickableText(
    text: ClickableTextValue,
    modifier: Modifier = Modifier,
    color: ColorResource? = null,
    lineBreak: LineBreak? = null,
    hyphens: Hyphens? = null,
    onClickAnnotatedText: (String) -> Unit = {},
    onClickUrl: (String) -> Unit = {},
    autoLinkUrls: Boolean = false,
    autoLinkAppearance: LinkAppearance = ClickableTextDefaults.urlLinkAppearance(),
    typography: TypographyResource = LocalTextConfig.current.typography
        ?: AppTheme.typography.Default,
    textDecoration: TextDecoration = LocalTextConfig.current.textDecoration ?: TextDecoration.None,
    textAlign: TextAlign? = LocalTextConfig.current.textAlign,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    overflow: TextOverflow = LocalTextConfig.current.overflow ?: DefaultTextOverflow,
    softWrap: Boolean = LocalTextConfig.current.softWrap ?: true,
    maxLines: Int = LocalTextConfig.current.maxLines ?: Int.MAX_VALUE,
) {
    val style = typography.toStyle(color, textDecoration, textAlign, hyphens, lineBreak)
    val autoLinkStyle = autoLinkAppearance.style
    val annotatedText = remember(text, autoLinkUrls, autoLinkStyle) {
        if (autoLinkUrls) text.text.detectAndAnnotateLinks(autoLinkStyle) else text.text
    }

    ClickableTextInternal(
        annotatedText = annotatedText,
        modifier = modifier,
        style = style,
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        onClickAnnotatedText = onClickAnnotatedText,
        onClickUrl = onClickUrl,
        clickHandlers = text.clickHandlers
    )
}

@Composable
private fun ClickableTextInternal(
    annotatedText: AnnotatedString,
    modifier: Modifier,
    style: TextStyle,
    onTextLayout: (TextLayoutResult) -> Unit,
    overflow: TextOverflow,
    softWrap: Boolean,
    maxLines: Int,
    onClickAnnotatedText: (String) -> Unit,
    onClickUrl: (String) -> Unit,
    clickHandlers: Map<String, ClickableTextSegment>
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val pressedScales = remember { mutableStateMapOf<String, Float>() }

    val updatedOnAnnotatedText = rememberUpdatedState(onClickAnnotatedText)
    val updatedOnUrl = rememberUpdatedState(onClickUrl)
    val updatedHandlers = rememberUpdatedState(clickHandlers)
    val updatedOnTextLayout = rememberUpdatedState(onTextLayout)

    val pressedSnapshot = pressedScales.toMap()
    val displayText = remember(annotatedText, pressedSnapshot) {
        if (pressedSnapshot.isEmpty()) annotatedText else annotatedText.withPressedScales(pressedSnapshot)
    }

    BasicText(
        modifier = modifier.pointerInput(annotatedText, clickHandlers) {
            detectTapGestures(
                onPress = { pressOffset ->
                    val layout = textLayoutResult ?: return@detectTapGestures
                    val offset = layout.getOffsetForPosition(pressOffset)
                    val annotation = annotatedText.getInteractiveAnnotation(offset)
                        ?: run {
                            tryAwaitRelease()
                            return@detectTapGestures
                        }

                    val handler = updatedHandlers.value[annotation.item]
                    val shouldBounce = annotation.tag == ANNOTATED_STRING_ON_CLICK_KEY && handler?.bounceOnPress == true

                    if (shouldBounce) {
                        animatePressedScale(pressedScales, annotation.item, handler!!.bounceScale)
                    }

                    val released = tryAwaitRelease()

                    if (shouldBounce) {
                        animatePressedScale(pressedScales, annotation.item, 1f)
                    }

                    if (!released) {
                        return@detectTapGestures
                    }

                    when (annotation.tag) {
                        ANNOTATED_STRING_ON_CLICK_KEY -> handler?.onClick?.invoke()
                        ANNOTATED_STRING_URL_KEY -> updatedOnUrl.value(annotation.item)
                        else -> updatedOnAnnotatedText.value(annotation.item)
                    }
                }
            )
        },
        text = displayText,
        style = style,
        onTextLayout = {
            textLayoutResult = it
            updatedOnTextLayout.value(it)
        },
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines
    )
}

private suspend fun animatePressedScale(
    pressedScales: MutableMap<String, Float>,
    key: String,
    target: Float
) {
    val start = pressedScales[key] ?: 1f
    if (start == target) return

    val animatable = Animatable(start)
    animatable.animateTo(target, animationSpec = tween(durationMillis = 110)) {
        if (value == 1f) {
            pressedScales.remove(key)
        } else {
            pressedScales[key] = value
        }
    }

    if (target == 1f) {
        pressedScales.remove(key)
    } else {
        pressedScales[key] = target
    }
}

private fun AnnotatedString.getInteractiveAnnotation(offset: Int): AnnotatedString.Range<String>? =
    getStringAnnotations(offset, offset).firstOrNull()

private fun AnnotatedString.withPressedScales(pressedScales: Map<String, Float>): AnnotatedString {
    if (pressedScales.isEmpty()) return this

    val builder = AnnotatedString.Builder(this)
    getStringAnnotations(ANNOTATED_STRING_ON_CLICK_KEY, 0, length)
        .filter { pressedScales[it.item]?.let { scale -> scale != 1f } == true }
        .forEach { range ->
            val scale = pressedScales[range.item] ?: 1f
            builder.addStyle(
                SpanStyle(textGeometricTransform = TextGeometricTransform(scaleX = scale)),
                start = range.start,
                end = range.end
            )
        }

    return builder.toAnnotatedString()
}

@Preview(heightDp = 900)
@Composable
private fun ClickableTextCatalogPreview() {
    PreviewContent {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ClickableText(
                text = "Visit https://cards.app/support or https://status.cards.app for updates.",
                onClickUrl = {}
            )

            val builderPrimary = buildClickableText("Manage your profile: Change email or delete account.") {
                link("Change email") {}
                dangerLink("delete account") {}
            }
            ClickableText(text = builderPrimary)

            val builderSubtle = buildClickableText("Need help? Read the FAQ or message support.") {
                subtleLink("Read the FAQ") {}
                link("message support") {}
            }
            ClickableText(text = builderSubtle, textAlign = TextAlign.Start)

            ClickableText(
                text = "Auto-linking disabled even though https://cards.app/docs is present.",
                autoLinkUrls = false
            )

            ClickableText(
                text = "Custom auto link style https://cards.app/pricing",
                autoLinkAppearance = ClickableTextDefaults.subtleLinkAppearance(),
                onClickUrl = {}
            )
        }
    }
}