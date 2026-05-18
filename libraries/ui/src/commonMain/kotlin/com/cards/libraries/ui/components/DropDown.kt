package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.material3.DropdownMenu as MaterialDropdownMenu
import androidx.compose.material3.DropdownMenuItem as MaterialDropdownMenuItem

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = AppTheme.colors.surfacePrimary.color,
        modifier = modifier,
        offset = offset,
        properties = properties,
        content = content
    )
}

@Composable
fun RoundedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialTheme(
        shapes = MaterialTheme.shapes.copy(
            extraSmall = RoundedCornerShape(
                topStart = 10.dp,
                bottomStart = 10.dp
            )
        )
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            content = content,
            modifier = modifier
        )
    }
}

@Composable
fun BasicDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    MaterialDropdownMenuItem(
        text = {
            CompositionLocalProvider(
                LocalTextStyle provides AppTheme.typography.Body.B700.Bold.style,
                content = text
            )
        },
        onClick = onClick,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        contentPadding = PaddingValues(
            horizontal = Dimension.D800,
            vertical = Dimension.D800
        ),
        interactionSource = interactionSource
    )
}

@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    MaterialDropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        interactionSource = interactionSource
    )
}

@Preview
@Composable
private fun PreviewBasicMenuItem() {
    PreviewContent {
        BasicDropdownMenuItem(
            onClick = {},
            text = { Text(text = "Example") }
        )
    }
}
