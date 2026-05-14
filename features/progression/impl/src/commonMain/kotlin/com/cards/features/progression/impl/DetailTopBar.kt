package com.dangerfield.cards.features.progression.impl

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

/**
 * Shared back-button + title top bar for the XP and Rank detail screens.
 * Lives in this module because both screens are progression-feature scoped;
 * if a third detail screen lands elsewhere we can lift this to libraries/ui.
 */
@Composable
internal fun DetailTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = AppTheme.colors.text.color,
            )
        }
        Spacer(modifier = Modifier.padding(start = 4.dp))
        Text(
            text = title,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
        )
    }
}
