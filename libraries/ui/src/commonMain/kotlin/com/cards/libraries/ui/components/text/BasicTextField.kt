package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.typography.TypographyResource
import com.dangerfield.cards.libraries.ui.PreviewContent
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun BasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    typographyToken: TypographyResource = LocalTextConfig.current.typography
        ?: AppTheme.typography.Default,
    color: Color = AppTheme.colors.text.color,
    disabledColor: Color = AppTheme.colors.textDisabled.color,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    cursorBrush: Brush = SolidColor(AppTheme.colors.accentPrimary.color),
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit =
        @Composable { innerTextField -> innerTextField() },
) {
    val textSelectionColors = TextSelectionColors(
        handleColor = AppTheme.colors.accentPrimary.color,
        backgroundColor = AppTheme.colors.accentPrimary.color.copy(alpha = 0.4F)
    )

    CompositionLocalProvider(LocalTextSelectionColors provides textSelectionColors) {
        SelectionContainer {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = modifier,
                enabled = enabled,
                readOnly = readOnly,
                textStyle = typographyToken.style(if (enabled) color else disabledColor),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                singleLine = singleLine,
                maxLines = maxLines,
                minLines = minLines,
                visualTransformation = visualTransformation,
                onTextLayout = onTextLayout,
                interactionSource = interactionSource,
                cursorBrush = cursorBrush,
                decorationBox = decorationBox
            )
        }
    }
}

@Composable
@Preview
private fun PreviewBasicTextField() {
    PreviewContent(modifier = Modifier.padding(24.dp)) {
        com.dangerfield.cards.libraries.ui.components.text.BasicTextField(
            value = "Hello World",
            onValueChange = { })
    }
}
