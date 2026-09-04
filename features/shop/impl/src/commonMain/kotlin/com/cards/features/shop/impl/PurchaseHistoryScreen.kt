package com.dangerfield.cards.features.shop.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.purchases_empty_message
import cards.libraries.resources.generated.resources.purchases_empty_title
import cards.libraries.resources.generated.resources.purchases_status_added
import cards.libraries.resources.generated.resources.purchases_status_pending
import cards.libraries.resources.generated.resources.purchases_status_refunded
import cards.libraries.resources.generated.resources.purchases_sync_button
import cards.libraries.resources.generated.resources.purchases_title
import cards.libraries.resources.generated.resources.purchases_unavailable_message
import cards.libraries.resources.generated.resources.purchases_unavailable_retry
import cards.libraries.resources.generated.resources.purchases_unavailable_title
import com.dangerfield.cards.libraries.billing.PurchaseHistoryItem
import com.dangerfield.cards.libraries.billing.PurchaseStatus
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ListItemAccessory
import com.dangerfield.cards.libraries.ui.components.ListSection
import com.dangerfield.cards.libraries.ui.components.ListSectionItem
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun PurchaseHistoryScreen(
    state: PurchaseHistoryState,
    onAction: (PurchaseHistoryAction) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onAction(PurchaseHistoryAction.Load) }
    PurchaseHistoryScreenContent(
        state = state,
        onSync = { onAction(PurchaseHistoryAction.Sync) },
        onRetry = { onAction(PurchaseHistoryAction.Load) },
        onBack = onBack,
    )
}

@Composable
private fun PurchaseHistoryScreenContent(
    state: PurchaseHistoryState,
    onSync: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Screen(
        topBar = { TopBar(title = stringResource(Res.string.purchases_title), onNavigateBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimension.D500, vertical = Dimension.D400),
        ) {
            when {
                state.items.isNotEmpty() -> ListSection(items = state.items.map { it.toRow() })
                state.unavailable -> CenteredNotice(
                    title = stringResource(Res.string.purchases_unavailable_title),
                    message = stringResource(Res.string.purchases_unavailable_message),
                )
                !state.loading -> CenteredNotice(
                    title = stringResource(Res.string.purchases_empty_title),
                    message = stringResource(Res.string.purchases_empty_message),
                )
            }

            VerticalSpacerD500()

            if (state.unavailable) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(Res.string.purchases_unavailable_retry))
                }
            } else {
                Button(onClick = onSync, enabled = !state.syncing, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(Res.string.purchases_sync_button))
                }
            }
        }
    }
}

@Composable
private fun CenteredNotice(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimension.D800),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimension.D200),
    ) {
        Text(text = title, typography = AppTheme.typography.Heading.H700, textAlign = TextAlign.Center)
        Text(
            text = message,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PurchaseHistoryItem.toRow(): ListSectionItem = ListSectionItem(
    headlineText = title,
    supportingText = stringResource(
        when (status) {
            PurchaseStatus.Added -> Res.string.purchases_status_added
            PurchaseStatus.Pending -> Res.string.purchases_status_pending
            PurchaseStatus.Refunded -> Res.string.purchases_status_refunded
        },
    ).let { statusLabel ->
        if (chips > 0) "${formatThousands(chips)} chips  ·  $statusLabel" else statusLabel
    },
    leadingContent = { Text(text = iconEmoji, typography = AppTheme.typography.Heading.H700) },
    accessory = ListItemAccessory.None,
)

@Preview
@Composable
private fun PurchaseHistoryScreenPreview_List() {
    PreviewContent {
        PurchaseHistoryScreenContent(
            state = PurchaseHistoryState(items = previewItems()),
            onSync = {}, onRetry = {}, onBack = {},
        )
    }
}

@Preview
@Composable
private fun PurchaseHistoryScreenPreview_Empty() {
    PreviewContent {
        PurchaseHistoryScreenContent(state = PurchaseHistoryState(), onSync = {}, onRetry = {}, onBack = {})
    }
}

@Preview
@Composable
private fun PurchaseHistoryScreenPreview_Unavailable() {
    PreviewContent {
        PurchaseHistoryScreenContent(
            state = PurchaseHistoryState(unavailable = true),
            onSync = {}, onRetry = {}, onBack = {},
        )
    }
}

private fun previewItems(): List<PurchaseHistoryItem> = listOf(
    PurchaseHistoryItem("t1", "chip_pack_medium", "Tall Stack", "💰", 30_000, PurchaseStatus.Added, 0),
    PurchaseHistoryItem("t2", "chip_pack_small", "Starter Stack", "🪙", 5_000, PurchaseStatus.Pending, 0),
    PurchaseHistoryItem("t3", "chip_pack_large", "High Roller", "💎", 100_000, PurchaseStatus.Refunded, 0),
)
