package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.VisualTransformation
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.libraries.ui.FieldState
import com.dangerfield.cards.libraries.ui.FieldState.Error
import com.dangerfield.cards.libraries.ui.FieldState.Invalid
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ErrorBehavior
import com.dangerfield.cards.libraries.ui.components.FormField
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun UnderlineInputField(
    fieldState: FieldState<String>,
    onFieldUpdated: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit = {},
    trailingIcon: @Composable (() -> Unit)? = null,
    subtitle: String? = null,
    errorBorder: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester = FocusRequester(),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    hint: String? = null,
    enabled: Boolean = true,
    isRequired: Boolean = false,
    errorBehavior: ErrorBehavior = ErrorBehavior.Show,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    FormField(
        modifier = modifier,
        formFieldState = fieldState,
        errorBehavior = errorBehavior,
        onFocusChanged = onFocusChanged,
    ) {
        Column {
            if (isRequired) {
                AsteriskText {
                    title()
                }
            } else {
                title()
            }

            if (subtitle != null) {
                VerticalSpacerD100()
                Text(
                    text = subtitle,
                    typography = AppTheme.typography.Body.B500
                )
                VerticalSpacerD300()
            }

            UnderlineTextField(
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                isError = errorBorder && (fieldState is Error),
                keyboardActions = keyboardActions,
                keyboardOptions = keyboardOptions,
                value = fieldState.value.orEmpty(),
                onValueChange = onFieldUpdated,
                enabled = enabled,
                visualTransformation = visualTransformation,
                trailingIcon = trailingIcon,
                placeholder = {
                    hint?.let { Text(text = it) }
                },
                singleLine = true
            )
        }
    }
}

@Composable
fun UnderlineInputField(
    title: String,
    fieldState: FieldState<String>,
    onFieldUpdated: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    focusRequester: FocusRequester = FocusRequester(),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    hint: String? = null,
    isRequired: Boolean = false,
    errorBehavior: ErrorBehavior = ErrorBehavior.Show,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    com.dangerfield.cards.libraries.ui.components.text.UnderlineInputField(
        title = { Text(text = title) },
        fieldState = fieldState,
        onFieldUpdated = onFieldUpdated,
        modifier = modifier,
        subtitle = subtitle,
        focusRequester = focusRequester,
        keyboardActions = keyboardActions,
        keyboardOptions = keyboardOptions,
        hint = hint,
        isRequired = isRequired,
        errorBehavior = errorBehavior,
        onFocusChanged = onFocusChanged
    )
}

@Composable
@Preview
private fun PreviewInputField() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.text.UnderlineInputField(
            title = "Title",
            fieldState = FieldState.Valid(""),
            onFieldUpdated = {},
            focusRequester = FocusRequester(),
            hint = "Hint",
            isRequired = true
        )
    }
}

@Composable
@Preview
private fun PreviewInputFieldLong() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.text.UnderlineInputField(
            fieldState = FieldState.Valid("This is more ext than you would expect yo be in a field"),
            onFieldUpdated = {},
            focusRequester = FocusRequester(),
            hint = "Hint",
            isRequired = true
        )
    }
}

@Composable
@Preview
private fun PreviewInputFieldError() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.text.UnderlineInputField(
            title = "Title",
            fieldState = Invalid("Bad Input", "This input is bad, do better."),
            onFieldUpdated = {},
            focusRequester = FocusRequester(),
            hint = "Hint",
            isRequired = true
        )
    }
}

@Composable
@Preview
private fun PreviewInputFieldNotRequired() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.text.UnderlineInputField(
            title = "Title",
            fieldState = FieldState.Valid(""),
            onFieldUpdated = {},
            focusRequester = FocusRequester(),
            hint = "Hint",
            isRequired = false
        )
    }
}

@Composable
@Preview
private fun PreviewInputFieldNotRequiredWSub() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.text.UnderlineInputField(
            title = "Title",
            subtitle = "Some smaller descriptive text",
            fieldState = FieldState.Valid(""),
            onFieldUpdated = {},
            focusRequester = FocusRequester(),
            hint = "Hint",
            isRequired = false
        )
    }
}

