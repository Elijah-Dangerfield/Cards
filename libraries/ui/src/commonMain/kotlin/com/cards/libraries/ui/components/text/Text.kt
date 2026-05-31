package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toUpperCase
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.libraries.ui.system.LocalContentColor
import com.dangerfield.cards.system.typography.TypographyResource
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@NonRestartableComposable
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: ColorResource? = null,
    lineBreak: LineBreak? = null,
    hyphens: Hyphens? = null,
    allCaps: Boolean? = LocalTextConfig.current.allCaps ?: false,
    typography: TypographyResource = LocalTextConfig.current.typography
        ?: AppTheme.typography.Default,
    textDecoration: TextDecoration = LocalTextConfig.current.textDecoration ?: TextDecoration.None,
    textAlign: TextAlign? = LocalTextConfig.current.textAlign,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    overflow: TextOverflow = LocalTextConfig.current.overflow ?: DefaultTextOverflow,
    softWrap: Boolean = LocalTextConfig.current.softWrap ?: true,
    maxLines: Int = LocalTextConfig.current.maxLines ?: Int.MAX_VALUE,
    minLines: Int = LocalTextConfig.current.minLines ?: 1,
) {
    val style = typography.toStyle(color, textDecoration, textAlign, hyphens, lineBreak)

    BasicText(
        text = text.processHtmlTags().let { if (allCaps == true) it.toUpperCase() else it },
        modifier = modifier,
        style = style,
        overflow = overflow,
        onTextLayout = onTextLayout,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines
    )
}

@NonRestartableComposable
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: ColorResource? = null,
    lineBreak: LineBreak? = null,
    hyphens: Hyphens? = null,
    allCaps: Boolean? = LocalTextConfig.current.allCaps ?: false,
    typography: TypographyResource = LocalTextConfig.current.typography
        ?: AppTheme.typography.Default,
    textDecoration: TextDecoration = LocalTextConfig.current.textDecoration ?: TextDecoration.None,
    textAlign: TextAlign? = LocalTextConfig.current.textAlign,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    overflow: TextOverflow = LocalTextConfig.current.overflow ?: DefaultTextOverflow,
    softWrap: Boolean = LocalTextConfig.current.softWrap ?: true,
    maxLines: Int = LocalTextConfig.current.maxLines ?: Int.MAX_VALUE,
    minLines: Int = LocalTextConfig.current.minLines ?: 1,
) {
    val style = typography.toStyle(color, textDecoration, textAlign, hyphens, lineBreak)

    BasicText(
        text = text.let { if (allCaps == true) it.toUpperCase() else it },
        modifier = modifier,
        style = style,
        overflow = overflow,
        onTextLayout = onTextLayout,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines
    )
}


@Composable
fun ProvideTextConfig(
    config: TextConfig,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTextConfig provides LocalTextConfig.current.merge(config),
        content = content
    )
}

@Composable
fun ProvideTextConfig(
    typography: TypographyResource? = null,
    color: ColorResource? = null,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    overflow: TextOverflow? = null,
    softWrap: Boolean? = null,
    allCaps: Boolean? = null,
    maxLines: Int? = null,
    minLines: Int? = null,
    content: @Composable () -> Unit,
) {
    ProvideTextConfig(
        config = LocalTextConfig.current.merge(
            color = color,
            typography = typography,
            textDecoration = textDecoration,
            textAlign = textAlign,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            allCaps = allCaps
        ),
        content = content
    )
}

internal val LocalTextConfig = compositionLocalOf { TextConfig.Companion.Default }

internal val DefaultTextOverflow = TextOverflow.Ellipsis

data class TextConfig(
    val typography: TypographyResource? = null,
    val color: ColorResource = ColorResource.Unspecified,
    val textDecoration: TextDecoration? = null,
    val textAlign: TextAlign? = null,
    val overflow: TextOverflow? = null,
    val allCaps: Boolean? = null,
    val softWrap: Boolean? = null,
    val maxLines: Int? = null,
    val minLines: Int? = null,
) {
    companion object {
        val Default = TextConfig()
    }

    fun merge(other: TextConfig?): TextConfig =
        when {
            other == null || other == Default -> this
            this == Default -> other
            else ->
                merge(
                    typography = other.typography,
                    color = other.color,
                    textDecoration = other.textDecoration,
                    textAlign = other.textAlign,
                    allCaps = other.allCaps,
                    overflow = other.overflow,
                    softWrap = other.softWrap,
                    maxLines = other.maxLines,
                    minLines = other.minLines
                )
        }

    fun merge(
        typography: TypographyResource?,
        color: ColorResource? = null,
        textDecoration: TextDecoration?,
        textAlign: TextAlign?,
        overflow: TextOverflow?,
        softWrap: Boolean?,
        allCaps: Boolean?,
        maxLines: Int?,
        minLines: Int?,
    ): TextConfig =
        TextConfig(
            typography = typography ?: this.typography,
            color = color ?: this.color,
            textDecoration = textDecoration ?: this.textDecoration,
            textAlign = textAlign ?: this.textAlign,
            overflow = overflow ?: this.overflow,
            allCaps = allCaps ?: this.allCaps,
            softWrap = softWrap ?: this.softWrap,
            maxLines = maxLines ?: this.maxLines,
            minLines = minLines ?: this.minLines
        )
}

@Composable
fun TypographyResource.toStyle(
    color: ColorResource?,
    textDecoration: TextDecoration?,
    textAlign: TextAlign?,
    hyphens: Hyphens? = null,
    lineBreak: LineBreak? = null,
): TextStyle {
    val fallbackColor =
        LocalTextConfig.current.color.takeOrElse(LocalContentColor.current.takeOrElse(AppTheme.colors.content))

    return style.copy(
        color = color?.takeOrElse(fallbackColor)?.color ?: fallbackColor.color,
        textDecoration = textDecoration,
        textAlign = textAlign ?: TextAlign.Start,
        hyphens = hyphens ?: style.hyphens,
        lineBreak = lineBreak ?: style.lineBreak
    )
}


private fun ColorResource.takeOrElse(default: ColorResource): ColorResource =
    this.takeIf { it.color.isSpecified } ?: default

@Preview
@Composable
private fun TextPreview() {
    PreviewContent(
        contentPadding = PaddingValues(Dimension.D500),
        
    ) {
        Text(
            allCaps = true,
            text = "This is something in all caps",
        )
    }
}


@Preview
@Composable
private fun TextPreviewProvided() {
    PreviewContent(
        contentPadding = PaddingValues(Dimension.D500),
        
    ) {
        ProvideTextConfig(
            config = TextConfig(
                typography = AppTheme.typography.Default
                    .copy(
                        fontWeight = FontWeight.ExtraLight
                    ),
                color = AppTheme.colors.accentPrimary,
                textDecoration = TextDecoration.Underline,
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
        ) {
            Text(
                "This is something",
            )
        }
    }
}

