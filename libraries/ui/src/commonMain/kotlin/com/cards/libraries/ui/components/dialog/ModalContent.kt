package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.color.ProvideContentColor
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ProvideButtonConfig
import com.dangerfield.cards.libraries.ui.components.text.ProvideTextConfig
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ModalContent(
    modifier: Modifier = Modifier,
    backgroundColor: ColorResource = AppTheme.colors.surface,
    contentColor: ColorResource = AppTheme.colors.content,
    topContent: @Composable () -> Unit = {},
    content: @Composable () -> Unit = {},
    bottomContent: @Composable (() -> Unit)? = null,
) {

    Column(
        modifier = modifier.background(backgroundColor.color)
    ) {

        ProvideContentColor(color = contentColor) {

            // Serif felt-signature headline — a bare Text in topContent is correct by default.
            ProvideTextConfig(AppTheme.typography.Display.D900.Italic) {
                topContent()
            }

            Spacer(modifier = Modifier.height(Dimension.D600))

            ProvideTextConfig(AppTheme.typography.Body.B500) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)

                ) {
                    content()
                }
            }

            if (bottomContent != null) {
                Spacer(modifier = Modifier.height(Dimension.D1000))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor.color),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ProvideButtonConfig(size = ButtonSize.Small) {
                        bottomContent()
                    }
                }

                Spacer(modifier = Modifier.height(Dimension.D800))
            }
        }
    }
}

@Composable
@Preview
private fun PreviewModalContent() {
    PreviewContent {
        ModalContent(
            modifier = Modifier,
            topContent = { Text(text = "Top Content") },
            content = {
                Column {
                    Text(text = "context".repeat(50))
                }
            },
            bottomContent = {
                Button(onClick = { }) {
                    Text(text = "Bottom Content")
                }
            },
        )
    }
}

@Composable
@Preview
private fun PreviewModalContentLong() {
    PreviewContent {
        ModalContent(
            modifier = Modifier,
            topContent = { Text(text = "Top Content") },
            content = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(text = "This is a bunch of words that take sus space".repeat(100))
                }
            },
            bottomContent = {
                Button(onClick = { }) {
                    Text(text = "Bottom Content")
                }
            },
        )
    }
}