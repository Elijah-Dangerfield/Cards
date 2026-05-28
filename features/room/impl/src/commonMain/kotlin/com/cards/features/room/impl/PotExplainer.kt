package com.dangerfield.cards.features.room.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_pot_explainer_body
import cards.libraries.resources.generated.resources.room_pot_explainer_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * One-tap explainer for "what is the pot?" — opened by tapping the gold pot
 * pill on the board. Kept short on purpose; the cheat sheet covers the rest
 * of the rules.
 */
@Composable
internal fun PotExplainer(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        title = {
            ChipCoin(size = 56.dp, textTypography = AppTheme.typography.Heading.H800)
            Text(
                text = stringResource(Res.string.room_pot_explainer_title),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
        },
        itemSpacing = Dimension.D700,
    ) {
        Text(
            text = stringResource(Res.string.room_pot_explainer_body),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun PotExplainerPreview() {
    PreviewContent {
        PotExplainer(onDismiss = {})
    }
}
