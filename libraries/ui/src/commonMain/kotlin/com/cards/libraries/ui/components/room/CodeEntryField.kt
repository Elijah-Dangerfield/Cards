package com.dangerfield.cards.libraries.ui.components.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii

/**
 * The room-code entry control: [length] big cells that fill left-to-right as
 * the player types, each typed character rendered in the gold italic serif of
 * the hero room code. The active (next-to-fill) cell gets a gold border + soft
 * glow.
 *
 * Implementation note — the handoff mock shows a bespoke on-screen numeric
 * keypad, but real room codes are **alphanumeric**, which a numeric pad can't
 * enter. So the cells overlay an invisible, auto-focused [BasicTextField] and
 * we let the platform keyboard (uppercased) drive input. This keeps the design
 * (cells + serif characters) while staying functional for the real join flow.
 * Input is filtered to A–Z/0–9 and capped at [length].
 */
@Composable
fun CodeEntryField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 4,
    autoFocus: Boolean = true,
    onImeDone: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Once the code is fully entered there's nothing left to type, so drop the
    // keyboard automatically — the user just taps the CTA. (Same on IME Done.)
    LaunchedEffect(value.length >= length) {
        if (value.length >= length) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }

    BasicTextField(
        value = value,
        onValueChange = { raw ->
            val cleaned = raw.uppercase().filter { it.isLetterOrDigit() }.take(length)
            onValueChange(cleaned)
        },
        modifier = modifier.focusRequester(focusRequester),
        singleLine = true,
        // Field itself is invisible; the cells below are the visible surface.
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
        textStyle = AppTheme.typography.Body.B400.style.copy(color = Color.Transparent),
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.Characters,
        ),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            focusManager.clearFocus()
            onImeDone()
        }),
        decorationBox = { innerTextField ->
            Box {
                // Invisible editing surface — keeps input/cursor wired while the
                // cells below are the visible representation.
                Box(Modifier.size(0.dp)) { innerTextField() }
                // Cells flex to share the available width so the field fits any
                // [length] on a phone — fixed-width cells overflowed off-screen
                // at 6 (the real room-code length), hiding the last one.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
                ) {
                    repeat(length) { i ->
                        CodeCell(
                            modifier = Modifier.weight(1f),
                            char = value.getOrNull(i),
                            active = i == value.length.coerceAtMost(length - 1) && value.length < length,
                        )
                    }
                }
            }
        },
    )

    LaunchedEffect(Unit) {
        if (autoFocus) focusRequester.requestFocus()
    }
}

@Composable
private fun CodeCell(char: Char?, active: Boolean, modifier: Modifier = Modifier) {
    val borderColor = when {
        char != null -> AppTheme.colors.accentPrimary
        active -> AppTheme.colors.accentPrimary
        else -> AppTheme.colors.border
    }
    Box(
        modifier = modifier
            .height(76.dp)
            .clip(Radii.R700.shape)
            .background(
                if (char != null) AppTheme.colors.accentPrimarySubtle.color else AppTheme.colors.surface.color,
            )
            .border(width = 2.dp, color = borderColor.color, shape = Radii.R700.shape),
        contentAlignment = Alignment.Center,
    ) {
        if (char != null) {
            Text(
                text = char.toString(),
                typography = AppTheme.typography.Display.D1200.Italic,
                color = AppTheme.colors.accentPrimary,
            )
        } else if (active) {
            // Caret hint
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(34.dp)
                    .background(AppTheme.colors.accentPrimary.color),
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun CodeEntryFieldPreview() {
    PreviewContent(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CodeEntryField(value = "AB", onValueChange = {}, length = 4, autoFocus = false)
            CodeEntryField(value = "WXYZ12", onValueChange = {}, length = 6, autoFocus = false)
        }
    }
}
