package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BalancePillSlot(
    chips: Long?,
    modifier: Modifier = Modifier,
    onChipsClick: (() -> Unit)? = null,
    leading: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        leading()
        Spacer(modifier = Modifier.weight(1f))
        ChipBadge(amount = chips, onClick = onChipsClick)
    }
}
