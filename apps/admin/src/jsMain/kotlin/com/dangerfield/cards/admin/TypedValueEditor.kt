package com.dangerfield.cards.admin

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.dom.TextInput

/**
 * One value editor for every flag type, shared by the server-value editor and
 * the rule editor. The draft the caller holds is always the JSON encoding, but
 * the operator never has to think in JSON: booleans and enums are buttons,
 * numbers are plain digits, and strings are typed without quotes (this editor
 * quotes/escapes them). Only `json`-typed flags fall back to a raw textarea.
 */
@Composable
internal fun TypedValueEditor(
    type: String?,
    allowedValues: List<String>?,
    draft: String,
    onDraft: (String) -> Unit,
) {
    when {
        type == "boolean" -> Div(attrs = { classes("row") }) {
            listOf("true", "false").forEach { option ->
                Button(attrs = {
                    if (draft.trim() == option) classes("primary")
                    onClick { onDraft(option) }
                }) { Text(option) }
            }
        }

        allowedValues != null -> Div(attrs = { classes("row") }) {
            allowedValues.forEach { option ->
                val json = JsonPrimitive(option).toString()
                Button(attrs = {
                    if (draft.trim() == json) classes("primary")
                    onClick { onDraft(json) }
                }) { Text(option) }
            }
        }

        type == "int" || type == "long" || type == "double" -> {
            TextInput(draft) { onInput { onDraft(it.value) }; placeholder(if (type == "double") "e.g. 1.5" else "e.g. 42") }
            InlineProblem(type, draft)
        }

        type == "string" -> {
            // Operator types the bare text; the draft stays JSON-encoded so
            // quoting/escaping is never their problem.
            val display = (parseJsonOrNull(draft) as? JsonPrimitive)?.contentOrNull ?: draft
            TextInput(display) { onInput { onDraft(JsonPrimitive(it.value).toString()) }; placeholder("text") }
        }

        else -> {
            TextArea(draft) { onInput { onDraft(it.value) }; placeholder("raw JSON") }
            InlineProblem(type, draft)
        }
    }
}

@Composable
private fun InlineProblem(type: String?, draft: String) {
    parseTypedValue(type, draft).problem?.let {
        Span(attrs = { classes("field-problem") }) { Text(it) }
    }
}
