package com.dangerfield.cards.system

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One named spacer per [Dimension] token. Prefer these over
 * `Spacer(Modifier.height(N.dp))` so vertical rhythm stays on the spacing
 * scale instead of drifting into ad-hoc numbers.
 *
 * Naming maps 1:1 to the dimension scale — e.g. [VerticalSpacerD600] is 14dp
 * (matches `Dimension.D600`). If a screen needs a value that doesn't map to
 * a token, round to the nearest one rather than introducing a one-off.
 */

@Composable
fun VerticalSpacerD50() {
    Spacer(modifier = Modifier.height(Dimension.D50))
}

@Composable
fun VerticalSpacerD100() {
    Spacer(modifier = Modifier.height(Dimension.D100))
}


@Composable
fun VerticalSpacerD200() {
    Spacer(modifier = Modifier.height(Dimension.D200))
}

@Composable
fun VerticalSpacerD300() {
    Spacer(modifier = Modifier.height(Dimension.D300))
}

@Composable
fun VerticalSpacerD400() {
    Spacer(modifier = Modifier.height(Dimension.D400))
}

@Composable
fun VerticalSpacerD500() {
    Spacer(modifier = Modifier.height(Dimension.D500))
}

@Composable
fun VerticalSpacerD600() {
    Spacer(modifier = Modifier.height(Dimension.D600))
}

@Composable
fun VerticalSpacerD700() {
    Spacer(modifier = Modifier.height(Dimension.D700))
}

@Composable
fun VerticalSpacerD800() {
    Spacer(modifier = Modifier.height(Dimension.D800))
}

@Composable
fun VerticalSpacerD850() {
    Spacer(modifier = Modifier.height(Dimension.D850))
}

@Composable
fun VerticalSpacerD900() {
    Spacer(modifier = Modifier.height(Dimension.D900))
}

@Composable
fun VerticalSpacerD1000() {
    Spacer(modifier = Modifier.height(Dimension.D1000))
}

@Composable
fun VerticalSpacerD1100() {
    Spacer(modifier = Modifier.height(Dimension.D1100))
}

@Composable
fun VerticalSpacerD1200() {
    Spacer(modifier = Modifier.height(Dimension.D1200))
}

@Composable
fun VerticalSpacerD1300() {
    Spacer(modifier = Modifier.height(Dimension.D1300))
}

@Composable
fun VerticalSpacerD1400() {
    Spacer(modifier = Modifier.height(Dimension.D1400))
}

@Composable
fun VerticalSpacerD1500() {
    Spacer(modifier = Modifier.height(Dimension.D1500))
}

@Composable
fun VerticalSpacerD1600() {
    Spacer(modifier = Modifier.height(Dimension.D1600))
}

@Composable
fun HorizontalSpacerD100() {
    Spacer(modifier = Modifier.width(Dimension.D100))
}

@Composable
fun HorizontalSpacerD200() {
    Spacer(modifier = Modifier.width(Dimension.D200))
}

@Composable
fun HorizontalSpacerD300() {
    Spacer(modifier = Modifier.width(Dimension.D300))
}

@Composable
fun HorizontalSpacerD400() {
    Spacer(modifier = Modifier.width(Dimension.D400))
}

@Composable
fun HorizontalSpacerD500() {
    Spacer(modifier = Modifier.width(Dimension.D500))
}

@Composable
fun HorizontalSpacerD600() {
    Spacer(modifier = Modifier.width(Dimension.D600))
}

@Composable
fun HorizontalSpacerD700() {
    Spacer(modifier = Modifier.width(Dimension.D700))
}

@Composable
fun HorizontalSpacerD800() {
    Spacer(modifier = Modifier.width(Dimension.D800))
}

@Composable
fun HorizontalSpacerD900() {
    Spacer(modifier = Modifier.width(Dimension.D900))
}

@Composable
fun HorizontalSpacerD1000() {
    Spacer(modifier = Modifier.width(Dimension.D1000))
}
