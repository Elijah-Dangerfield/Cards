package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToFeedback: () -> Unit,
    onNavigateToBugReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()

    Screen(modifier = modifier) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Welcome to Cards",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.text,
                textAlign = TextAlign.Center,
            )

            VerticalSpacerD800()

            if (state.userName != null) {
                Text(
                    text = "Hello, ${state.userName}!",
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.textSecondary,
                )
                VerticalSpacerD500()
            }

            Text(
                text = "This is your starting point.\nBuild something amazing!",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            VerticalSpacerD800()

            Button(onClick = onNavigateToFeedback) {
                Text("Send Feedback")
            }

            VerticalSpacerD500()

            Button(onClick = onNavigateToBugReport) {
                Text("Report a Bug")
            }
        }
    }
}