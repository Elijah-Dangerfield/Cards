package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.VisualTransformation
import com.dangerfield.cards.libraries.ui.FieldState
import com.dangerfield.cards.libraries.ui.FieldState.Error
import com.dangerfield.cards.libraries.ui.FieldState.Invalid
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ErrorBehavior
import com.dangerfield.cards.libraries.ui.components.FormField
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun OutlineInputField(
    fieldState: FieldState<String>,
    modifier: Modifier = Modifier,
    onFieldUpdated: (String) -> Unit = {},
    label: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    errorBorder: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester = FocusRequester(),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    hint: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    errorBehavior: ErrorBehavior = ErrorBehavior.Show,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    FormField(
        modifier = modifier,
        formFieldState = fieldState,
        errorBehavior = errorBehavior,
        onFocusChanged = onFocusChanged,
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            isError = errorBorder && (fieldState is Error),
            keyboardActions = keyboardActions,
            keyboardOptions = keyboardOptions,
            value = fieldState.value.orEmpty(),
            onValueChange = onFieldUpdated,
            enabled = enabled,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            label = label,
            placeholder = {
                hint?.let { Text(text = it) }
            },
            singleLine = singleLine
        )
    }
}



@Composable
@Preview
private fun PreviewInputField() {
    PreviewContent {
        OutlineInputField(
            label = { Text("Title") },
            fieldState = FieldState.Valid(""),
            onFieldUpdated = {},
            focusRequester = FocusRequester(),
            hint = "Hint",
        )
    }
}

@Composable
@Preview
private fun PreviewInputFieldError() {
    PreviewContent {
        OutlineInputField(
            label = { Text("Title") },
            fieldState = Invalid("Bad Input", "This input is bad, do better."),
            onFieldUpdated = {},
            focusRequester = FocusRequester(),
            hint = "Hint",
        )
    }
}

@Composable
@Preview
private fun PreviewInputFieldNotRequired() {
    PreviewContent {
        OutlineInputField(
            fieldState = FieldState.Valid(""),
            onFieldUpdated = {},
            focusRequester = FocusRequester(),
            hint = "Hint",
        )
    }
}

@Composable
@Preview
private fun PreviewInputFieldNotRequiredWSub() {
    PreviewContent {
        OutlineInputField(
            label = { Text("Title") },
            fieldState = FieldState.Valid(""),
            onFieldUpdated = {},
            focusRequester = FocusRequester(),
            hint = "Hint",
        )
    }
}

