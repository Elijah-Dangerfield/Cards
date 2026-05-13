package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.hideFromAccessibility
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.typography.TypographyResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun OutlinedText(
    text: String,
    modifier: Modifier = Modifier,
    strokeColor: ColorResource = AppTheme.colors.accentPrimary,
    strokeWidth: Dp = Dimension.D25,
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
    val baseStyle: TextStyle =
        typography.toStyle(color, textDecoration, textAlign, hyphens, lineBreak)

    val processedText = text
        .processHtmlTags()
        .let { if (allCaps == true) it.toUpperCase() else it }

    // If stroke is "off", just render regular text
    if (strokeColor == ColorResource.Unspecified || strokeWidth <= 0.dp) {
        BasicText(
            text = processedText,
            modifier = modifier,
            style = baseStyle,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout
        )
        return
    }

    val density = LocalDensity.current
    val outlineStroke = Stroke(
        width = with(density) { strokeWidth.toPx() },
        join = StrokeJoin.Round
    )

    val outlineStyle = baseStyle.copy(
        color = strokeColor.color,
        drawStyle = outlineStroke,
        shadow = null // important so you don't get extra blur
    )

    Box(modifier = modifier) {
        // Outline-only text (hidden from semantics)
        BasicText(
            text = processedText,
            style = outlineStyle,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            modifier = Modifier.semantics { hideFromAccessibility() },
            onTextLayout = {} // ignore; we use the filled one
        )

        // Fill text on top
        BasicText(
            text = processedText,
            style = baseStyle,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout
        )
    }
}

@Composable
@Preview
private fun PreviewOutlineText() {
    PreviewContent {
        OutlinedText(
            "Hello!",
            strokeWidth = Dimension.D500,
            typography = AppTheme.typography.Display.D1500
        )
    }
}
