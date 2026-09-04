package com.dangerfield.cards.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Surface
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii

/**
 * Debug-only list of StrictMode violations this run.
 *
 * Deliberately plain: no design-system polish, no empty-state illustration.
 * Nobody but us will ever see it, and every minute spent making it pretty is a
 * minute not spent on the violations it lists.
 *
 * Opening the screen is the acknowledgement — [onSeen] fires once on entry, so
 * everything here stops counting as new and the badge clears. That is the whole
 * interaction: the toast tells you something changed, this tells you what, and
 * reading it is what makes it stop asking.
 */
// Inline strings on purpose. `VerifyStrings` exists so user-facing copy is
// translatable, and none of this is user-facing: the screen only exists in debug
// builds and only we will ever open it. Putting developer text into the shipped
// resource set would push it at translators and grow every locale for nothing.
@Suppress("VerifyStrings")
@Composable
fun PerformanceLogScreen(
    violations: List<StrictModeViolation>,
    onSeen: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onSeen() }

    Screen(
        topBar = { TopBar(title = "Performance log", onNavigateBack = onBack) },
    ) {
        if (violations.isEmpty()) {
            Text(
                text = "No StrictMode violations this run.",
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
            return@Screen
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            items(violations, key = { it.signature }) { violation ->
                Surface(
                    color = AppTheme.colors.surfaceRaised,
                    contentColor = AppTheme.colors.content,
                    radius = Radii.R600,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            // The badge is the only affordance that says "this
                            // is the one that appeared today".
                            text = if (violation.isNew) "NEW · ${violation.kind}" else violation.kind,
                            typography = AppTheme.typography.Body.B600.SemiBold,
                            color = if (violation.isNew) {
                                AppTheme.colors.accentPrimary
                            } else {
                                AppTheme.colors.content
                            },
                        )
                        Text(
                            text = violation.origin,
                            typography = AppTheme.typography.Body.B500,
                            color = AppTheme.colors.contentSecondary,
                        )
                        Text(
                            text = "${violation.count}×",
                            typography = AppTheme.typography.Body.B400,
                            color = AppTheme.colors.contentSecondary,
                        )
                    }
                }
            }
        }
    }
}
