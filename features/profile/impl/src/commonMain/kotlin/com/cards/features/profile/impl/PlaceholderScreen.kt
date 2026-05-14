package com.dangerfield.cards.features.profile.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

/**
 * Generic "coming soon" placeholder. Used for profile sub-routes whose real
 * implementation lands later (edit profile, gameplay speed, delete account,
 * claim account). Wires the navigation graph end-to-end now so the routes
 * are exercised by integration tests and the empty-cell deep links don't
 * crash; each one swaps in real content as it's built.
 */
@Composable
internal fun PlaceholderScreen(
    title: String,
    body: String,
    onBack: () -> Unit,
) {
    Screen(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
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
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "🚧",
                    typography = AppTheme.typography.Display.D1200,
                    color = AppTheme.colors.text,
                )
                Text(
                    text = title,
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.text,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = body,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
