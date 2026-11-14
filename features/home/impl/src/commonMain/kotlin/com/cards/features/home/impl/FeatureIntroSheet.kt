package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.feature_intro_tag_new
import cards.libraries.resources.generated.resources.feature_intro_standing_title
import cards.libraries.resources.generated.resources.feature_intro_standing_body
import cards.libraries.resources.generated.resources.feature_intro_standing_sample_name
import cards.libraries.resources.generated.resources.feature_intro_standing_primary
import cards.libraries.resources.generated.resources.feature_intro_standing_dismiss
import cards.libraries.resources.generated.resources.standing_table_home_tonight
import com.dangerfield.cards.features.home.impl.notification.FeatureIntro
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.StatusPill
import com.dangerfield.cards.libraries.ui.components.button.ButtonGhost
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.room.StandingTableCard
import com.dangerfield.cards.libraries.ui.components.room.standingScheduleLabel
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
internal fun FeatureIntroSheet(intro: FeatureIntro, onPrimary: () -> Unit, onDismiss: () -> Unit) {
    when (intro) {
        FeatureIntro.StandingTables -> BottomSheet(
            title = stringResource(Res.string.feature_intro_standing_title),
            onDismissRequest = onDismiss,
            backgroundColor = AppTheme.colors.background,
            dragHandle = topAccessoryEmoji(emoji = "🗓️").asDragHandle(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Dimension.D500),
            ) {
                StatusPill(background = AppTheme.colors.surfaceHigh) {
                    Text(
                        text = stringResource(Res.string.feature_intro_tag_new),
                        typography = AppTheme.typography.Label.L300,
                        color = AppTheme.colors.accentPrimary,
                    )
                }
                Text(
                    text = stringResource(Res.string.feature_intro_standing_body),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                )
                StandingTableCard(
                    name = stringResource(Res.string.feature_intro_standing_sample_name),
                    schedule = standingScheduleLabel(DayOfWeek.THURSDAY, LocalTime(20, 0)),
                    status = stringResource(Res.string.standing_table_home_tonight),
                    onClick = onPrimary,
                )
                ButtonPrimary(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.feature_intro_standing_primary))
                }
                ButtonGhost(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.feature_intro_standing_dismiss))
                }
            }
        }
    }
}

@Preview
@Composable
private fun FeatureIntroSheetPreview_StandingTables() {
    PreviewContent { FeatureIntroSheet(FeatureIntro.StandingTables, {}, {}) }
}
