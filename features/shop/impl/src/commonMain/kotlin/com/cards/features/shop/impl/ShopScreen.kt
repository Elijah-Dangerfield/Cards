package com.dangerfield.cards.features.shop.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import com.dangerfield.cards.libraries.ui.components.ChipBadge
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

@Composable
fun ShopScreen(modifier: Modifier = Modifier) {
    Screen(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ChipBadge(amount = 10_000)
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Shop",
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.text,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Cosmetics and table styles coming soon",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(48.dp))
            ComingSoonCard()
        }
    }
}

@Composable
private fun ComingSoonCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppTheme.colors.surfacePrimary.color)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xFFE0B863).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🛍️",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.text,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Nothing for sale yet",
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Card backs, avatars, and table themes will live here. We'll let you know.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun ShopScreenPreview() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        ShopScreen()
    }
}
