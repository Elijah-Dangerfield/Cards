package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider as MaterialHorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.0.dp,
    color: ColorResource = AppTheme.colors.border,
) {
    MaterialHorizontalDivider(
        modifier = modifier,
        thickness = thickness,
        color = color.color
    )
}

@Composable
@Preview
fun PreviewDivider() {
    PreviewContent {
        Column {
            Text(text = "Text")
            Spacer(modifier = Modifier.height(Dimension.D500))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(Dimension.D500))
            Text(text = "Text")
        }
    }
}