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
        // Center against the leading element so the chip pill sits at the same
        // vertical band as Home's header chip (which centers on the avatar row),
        // reading as one persistent wallet affordance across the two tabs. Keep
        // the leading slot a single line (e.g. just a title) so "center" lands
        // where intended; stack any supporting copy below this row.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(modifier = Modifier.weight(1f))
        ChipBadge(amount = chips, onClick = onChipsClick)
    }
}
