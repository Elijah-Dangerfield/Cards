package com.dangerfield.cards.features.progression.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii

@Composable
internal fun InfoCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surfaceRaised.color)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
internal fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "·",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
        )
    }
}
