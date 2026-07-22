package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * A write waiting for the operator's go-ahead. Every mutation the console makes
 * flows through one of these so the operator always sees what is about to
 * change, on which environment, from what to what — before it happens.
 */
internal class PendingWrite(
    val title: String,
    val envName: String,
    val isProd: Boolean,
    val flagPath: String?,
    val before: String,
    val after: String,
    /** Non-null for the flags that hit every user hard; shown big and red. */
    val warning: String? = null,
    /** Dangerous writes on prod demand typing the env name to arm Confirm. */
    val requireEnvTyping: Boolean = false,
    val onConfirm: () -> Unit,
)

@Composable
internal fun ConfirmModal(pending: PendingWrite?, onDismiss: () -> Unit) {
    if (pending == null) return
    var typedEnv by remember(pending) { mutableStateOf("") }
    val armed = !pending.requireEnvTyping || typedEnv.trim().equals(pending.envName, ignoreCase = true)

    DisposableEffect(pending) {
        document.body?.classList?.add("modal-open")
        val onKey: (Event) -> Unit = { event ->
            if ((event as? KeyboardEvent)?.key == "Escape") onDismiss()
        }
        document.addEventListener("keydown", onKey)
        onDispose {
            document.body?.classList?.remove("modal-open")
            document.removeEventListener("keydown", onKey)
        }
    }

    Div(attrs = { classes("modal-backdrop"); onClick { onDismiss() } }) {
        Div(attrs = {
            classes("modal", if (pending.warning != null) "modal-danger" else "modal-normal")
            // Keep clicks inside the sheet from falling through to the backdrop.
            onClick { it.stopPropagation() }
        }) {
            Div(attrs = { classes("row") }) {
                H2(attrs = { classes("modal-title") }) { Text(pending.title) }
                Div(attrs = { classes("spacer") }) {}
                Span(attrs = { classes("env-chip", if (pending.isProd) "env-chip-prod" else "env-chip-dev") }) {
                    Text(pending.envName.uppercase())
                }
            }
            pending.flagPath?.let { Div { Span(attrs = { classes("flag-path") }) { Text(it) } } }

            Div(attrs = { classes("layers") }) {
                Layer("before", pending.before)
                Span(attrs = { classes("muted", "modal-arrow") }) { Text("→") }
                Layer("after", pending.after)
            }

            pending.warning?.let {
                P(attrs = { classes("modal-warning") }) { Text(it) }
            }
            if (pending.requireEnvTyping) {
                Div(attrs = { classes("row") }) {
                    Span(attrs = { classes("muted") }) { Text("Type \"${pending.envName}\" to confirm:") }
                    TextInput(typedEnv) { onInput { typedEnv = it.value }; placeholder(pending.envName) }
                }
            }

            Div(attrs = { classes("row", "modal-actions") }) {
                Button(attrs = { onClick { onDismiss() } }) { Text("Cancel") }
                Button(attrs = {
                    classes(if (pending.warning != null) "danger-solid" else "primary")
                    if (!armed) attr("disabled", "true")
                    onClick {
                        if (armed) {
                            pending.onConfirm()
                            onDismiss()
                        }
                    }
                }) { Text(if (pending.warning != null) "Yes, do it" else "Confirm") }
            }
        }
    }
}

@Composable
private fun Layer(label: String, value: String) {
    Div(attrs = { classes("layer") }) {
        Span(attrs = { classes("muted") }) { Text(label) }
        Span(attrs = { classes(if (value == "not set") "val-not-set" else "val") }) { Text(value) }
    }
}
