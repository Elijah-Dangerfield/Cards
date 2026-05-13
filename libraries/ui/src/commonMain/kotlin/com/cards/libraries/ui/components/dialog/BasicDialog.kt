package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun BasicDialog(
    state: DialogState = rememberDialogState(),
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: ModalDialogProperties = ModalDialogProperties(),
    animationSpec: ModalDialogAnimationSpec = ModalDialogAnimationSpec(),
    scrimColor: Color = ModalDialogDefaults.scrimColor(),
    contentAlignment: Alignment = Alignment.Center,
    topContent: @Composable () -> Unit = {},
    content: @Composable () -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    Dialog(
        state = state,
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        properties = properties,
        animationSpec = animationSpec,
        scrimColor = scrimColor,
        contentAlignment = contentAlignment
    ) {
        ModalContent(
            modifier = modifier.padding(
                top = Dimension.D800,
                start = Dimension.D800,
                end = Dimension.D800,
                bottom = Dimension.D800
            ),
            topContent = topContent,
            content = content,
            bottomContent = bottomContent
        )
    }
}

@Composable
fun BasicDialog(
    title: String,
    description: String,
    primaryButtonText: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    state: DialogState = rememberDialogState(),
    secondaryButtonText: String? = null,
    onPrimaryButtonClicked: () -> Unit,
    onSecondaryButtonClicked: (() -> Unit)? = null,
    properties: ModalDialogProperties = ModalDialogProperties(),
    animationSpec: ModalDialogAnimationSpec = ModalDialogAnimationSpec(),
    scrimColor: Color = ModalDialogDefaults.scrimColor(),
    contentAlignment: Alignment = Alignment.Center,
) {
    BasicDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        state = state,
        properties = properties,
        animationSpec = animationSpec,
        scrimColor = scrimColor,
        contentAlignment = contentAlignment,
        topContent = { Text(text = title) },
        content = { Text(text = description) },
        bottomContent = {
            Column(
                modifier = Modifier.padding(horizontal = Dimension.D1000)
            ) {
                Button(
                    size = ButtonSize.Medium,
                    onClick = onPrimaryButtonClicked,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = primaryButtonText)
                }

                if (secondaryButtonText != null && onSecondaryButtonClicked != null) {

                    Spacer(modifier = Modifier.height(Dimension.D600))

                    Button(
                        size = ButtonSize.Medium,
                        type = ButtonType.Tertiary,
                        onClick = onSecondaryButtonClicked,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = secondaryButtonText)
                    }
                }
            }
        },
    )
}


@Composable
@Preview
private fun PreviewDialog() {
    PreviewContent {
        BasicDialog(
            onDismissRequest = { -> },
            modifier = Modifier,
            topContent = { Text(text = "Top Content") },
            content = {
                Column {
                    Text(text = "content".repeat(10))
                    Text(text = "is good".repeat(10))
                }
            },
            bottomContent = {
                Button(onClick = { /*TODO*/ }) {
                    Text(text = "Bottom Content")
                }
            },
        )
    }
}


@Composable
@Preview
private fun PreviewBasicDialog() {
    PreviewContent {
        BasicDialog(
            onDismissRequest = { -> },
            title = "This is a title",
            description = "this is a description, pretty cool right? ",
            primaryButtonText = "No",
            secondaryButtonText = "Yes",
            onPrimaryButtonClicked = {},
            onSecondaryButtonClicked = {},
        )
    }
}

@Composable
@Preview
@Suppress("MagicNumber")
private fun PreviewBasicDialogLongDescription() {
    PreviewContent {
        BasicDialog(
            onDismissRequest = { -> },
            title = "This is a title",
            description = "this is a description thats super long.".repeat(50),
            primaryButtonText = "No",
            secondaryButtonText = "Yes",
            onPrimaryButtonClicked = {},
            onSecondaryButtonClicked = {},
        )
    }
}
