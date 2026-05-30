package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_password_hide_a11y
import cards.libraries.resources.generated.resources.ui_password_show_a11y
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Password input with a built-in show / hide visibility toggle on the
 * trailing edge. Wraps the DS [OutlinedTextField] so callers get the
 * Cards typography, border, and focus treatment for free.
 *
 * The toggle owns its own state via [rememberSaveable] so the
 * visibility choice survives configuration changes without leaking
 * the cleartext into the host ViewModel's state.
 */
@Composable
fun PasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    helper: String? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val showLabel = stringResource(Res.string.ui_password_show_a11y)
    val hideLabel = stringResource(Res.string.ui_password_hide_a11y)

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            isError = isError,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onImeAction() },
                onGo = { onImeAction() },
                onNext = { onImeAction() },
            ),
            label = { Text(label) },
            trailingIcon = {
                val icon = if (visible) Icons.VisibilityOff else Icons.Visibility
                val description = if (visible) hideLabel else showLabel
                IconButton(
                    icon = icon(description),
                    onClick = { visible = !visible },
                    backgroundColor = null,
                    size = IconButton.Size.Small,
                    enabled = enabled,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        helper?.let {
            Spacer(modifier = Modifier.height(Dimension.D200))
            Text(
                text = it,
                typography = AppTheme.typography.Body.B400,
                color = if (isError) AppTheme.colors.danger else AppTheme.colors.contentSecondary,
            )
        }
    }
}

@Preview
@Composable
private fun PasswordTextFieldPreview_Empty() {
    PreviewContent(modifier = Modifier.padding(24.dp)) {
        PasswordTextField(
            value = "",
            onValueChange = {},
            label = "Password",
        )
    }
}

@Preview
@Composable
private fun PasswordTextFieldPreview_Hidden() {
    PreviewContent(modifier = Modifier.padding(24.dp)) {
        PasswordTextField(
            value = "hunter2",
            onValueChange = {},
            label = "Password",
        )
    }
}

@Preview
@Composable
private fun PasswordTextFieldPreview_WithHelper() {
    PreviewContent(modifier = Modifier.padding(24.dp)) {
        PasswordTextField(
            value = "hunter2",
            onValueChange = {},
            label = "Password",
            helper = "At least 8 characters.",
        )
    }
}

@Preview
@Composable
private fun PasswordTextFieldPreview_Error() {
    PreviewContent(modifier = Modifier.padding(24.dp)) {
        PasswordTextField(
            value = "short",
            onValueChange = {},
            label = "Password",
            helper = "Use at least 8 characters.",
            isError = true,
        )
    }
}
