package com.dangerfield.cards.libraries.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dangerfield.cards.libraries.ui.components.Banner
import com.dangerfield.cards.libraries.ui.components.BannerType
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

private const val BANNER_SUBTITLE =
    "Inline, non-modal messages. Six tones built only from status subtle-fill + solid-edge tokens " +
        "(Promo uses the gold gradient). Optional leading well + trailing action slot."

/** The banner page body. Reused by [DesignSystemPreview]. */
@Composable
internal fun BannerCatalogBody() {
    CatalogSection(
        "Tones",
        "Pick by meaning: Info (neutral), Success (done), Warning (caution), Danger (error), Promo (sell), Trust (sign-in / security).",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimension.D600),
        ) {
            Banner(BannerType.Info, "Heads up", "Tournaments start at the top of the hour.")
            Banner(BannerType.Success, "Saved", "Your progress is synced across devices.")
            Banner(BannerType.Warning, "Low balance", "You're running low on chips.")
            Banner(BannerType.Danger, "Action needed", "Your last hand could not be recorded.")
            Banner(BannerType.Promo, "Go Premium", "Unlock private tables and custom felt.")
            Banner(BannerType.Trust, "Save your progress", "Create an account to keep your stats.")
        }
    }

    CatalogSection(
        "With leading well + trailing action",
        "Add a leading emoji/icon well for a glanceable cue, and a trailing Button for the call to action.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimension.D600),
        ) {
            Banner(
                type = BannerType.Promo,
                title = "Daily reward",
                body = "Claim your 5,000 chip bonus before midnight.",
                leading = { Text("🪙") },
                action = {
                    Button(type = ButtonType.Primary, size = ButtonSize.Small, onClick = {}) { Text("Claim") }
                },
            )
            Banner(
                type = BannerType.Info,
                title = "New table type",
                body = "Short-deck is now available in casual play.",
                leading = { Text("🃏") },
                action = {
                    Button(type = ButtonType.Ghost, size = ButtonSize.Small, onClick = {}) { Text("View") }
                },
            )
        }
    }
}

@Preview(widthDp = 900, heightDp = 1500)
@Composable
private fun BannerCatalog() {
    CatalogPage(title = "Banner", subtitle = BANNER_SUBTITLE) { BannerCatalogBody() }
}
