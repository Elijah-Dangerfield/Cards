package com.dangerfield.cards.features.profile.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.cards.xpAtStartOfLevel
import com.dangerfield.cards.libraries.ui.system.LocalLevelCurve
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.ConfigOverride
import com.dangerfield.cards.libraries.config.ConfigOverrideRepository
import com.dangerfield.cards.libraries.config.ConfiguredValue
import com.dangerfield.cards.libraries.config.FlagConfigValue
import com.dangerfield.cards.libraries.config.IntConfigValue
import com.dangerfield.cards.libraries.config.QaConfigValue
import com.dangerfield.cards.libraries.config.StringConfigValue
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Switch
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.libraries.ui.components.text.BasicTextField
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
fun QaMenuScreen(
    configStream: Flow<AppConfigMap>,
    initialConfig: AppConfigMap,
    configuredValues: Set<QaConfigValue>,
    overrideRepository: ConfigOverrideRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    userId: String? = null,
    appVersion: String = "",
    backendEnv: String = "",
    totalXp: Long = 0L,
    onSetTotalXp: (Long) -> Unit = {},
    onActivateXpBoost: () -> Unit = {},
    onSubmitFeedback: (String) -> Unit = {},
    // Debug-only: opens the on-device network inspector (Wiretap). Null in
    // release, where the button doesn't render.
    onOpenNetworkInspector: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val configMap by configStream.collectAsState(initial = initialConfig)

    val rows = remember(configMap, configuredValues) { buildRows(configuredValues, configMap) }

    val drafts = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(rows) {
        rows.forEach { row ->
            // Always sync drafts to live values so the editor reflects what's currently active.
            drafts[row.path] = row.currentValue.toString()
        }
    }

    val scrollState = rememberScrollState()
    Screen(
        modifier = modifier,
        topBar = {
            TopBar(
                title = "QA menu",
                onNavigateBack = onBack,
                scrollState = scrollState,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .screenContentPadding(paddingValues = padding),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            QaInfoBlock(
                appVersion = appVersion,
                backendEnv = backendEnv,
                userId = userId,
            )

            QaFeedbackBlock(onSubmit = onSubmitFeedback)

            Text(
                text = "Override any server-driven config value for this session. Cleared on uninstall.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )

            ProgressionDebugBlock(
                totalXp = totalXp,
                onSetTotalXp = onSetTotalXp,
                onActivateXpBoost = onActivateXpBoost,
            )

            if (onOpenNetworkInspector != null) {
                Box(
                    modifier = Modifier
                        .clip(Radii.R400.shape)
                        .background(AppTheme.colors.surfaceRaised.color)
                        .clickable(onClick = onOpenNetworkInspector)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Network inspector",
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.content,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(Radii.R400.shape)
                    .background(AppTheme.colors.surfaceRaised.color)
                    .clickable {
                        scope.launch { overrideRepository.clearAll() }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Clear all overrides",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.content,
                )
            }

            rows.groupBy { it.group }.forEach { (group, groupRows) ->
                QaSection(title = group) {
                    groupRows.forEach { row ->
                        QaRow(
                            row = row,
                            draft = drafts[row.path] ?: row.currentValue.toString(),
                            onDraftChange = { drafts[row.path] = it },
                            onApply = { value ->
                                scope.launch {
                                    overrideRepository.addOverride(ConfigOverride(row.path, value))
                                }
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

/**
 * Top-of-screen "who/what/where am I" card: the same version string shown at
 * the bottom of Settings, the backend environment this build talks to, and the
 * signed-in user id (long-press to copy — the number a tester reads back when
 * reporting an issue).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QaInfoBlock(
    appVersion: String,
    backendEnv: String,
    userId: String?,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val userIdClickable = if (userId != null) {
        Modifier.combinedClickable(
            onClick = {},
            onLongClick = {
                clipboard.setText(AnnotatedString(userId))
                copied = true
            },
        )
    } else {
        Modifier
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R400.shape)
            .background(AppTheme.colors.surface.color)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoRow(label = "Version", value = appVersion.ifBlank { "—" })
        InfoRow(label = "Backend", value = backendEnv.ifBlank { "—" })

        // User id can be a full UUID, so stack it label-over-value instead of a
        // single row — a side-by-side layout would overflow or truncate it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(userIdClickable),
        ) {
            Text(
                text = "User ID",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = userId ?: "— not signed in —",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.content,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    userId == null -> "Profile not resolved yet — check ProfileRepository."
                    copied -> "Copied"
                    else -> "Long-press to copy"
                },
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
        )
    }
}

/**
 * Quick feedback box. Routes through the same [FeedbackRepository] the Settings
 * feedback form uses, so a note here lands in Sentry exactly like in-app
 * feedback — just without the email/screenshot fields.
 */
@Composable
private fun QaFeedbackBlock(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val canSend = text.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Feedback",
            typography = AppTheme.typography.Heading.H500,
            color = AppTheme.colors.content,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.R700.shape)
                .background(AppTheme.colors.surface.color)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Goes straight to Sentry, same as in-app feedback.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                placeholder = { Text("What's up?") },
                singleLine = false,
                minLines = 3,
                maxLines = 6,
            )
            Box(
                modifier = Modifier
                    .clip(Radii.R400.shape)
                    .background(
                        if (canSend) AppTheme.colors.accentPrimary.color
                        else AppTheme.colors.surfaceRaised.color,
                    )
                    .clickable(enabled = canSend) {
                        onSubmit(text.trim())
                        text = ""
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Send",
                    typography = AppTheme.typography.Body.B500,
                    color = if (canSend) AppTheme.colors.onAccentPrimary
                    else AppTheme.colors.contentSecondary,
                )
            }
        }
    }
}

/**
 * Manual XP override block. Two inputs because both modes come up while
 * testing — sometimes you want "drop me at level 7 exactly," sometimes you
 * want "set XP to 12,345 to verify the comma formatting." Setting level
 * jumps to that level's start XP; setting XP just writes the raw value.
 *
 * Bypasses the XP ledger by design — gameplay never calls into this path.
 */
@Composable
private fun ProgressionDebugBlock(
    totalXp: Long,
    onSetTotalXp: (Long) -> Unit,
    onActivateXpBoost: () -> Unit,
) {
    val levelCurve = LocalLevelCurve.current
    val progress = remember(totalXp, levelCurve) { levelProgressFor(totalXp, levelCurve) }
    var xpDraft by remember(totalXp) { mutableStateOf(totalXp.toString()) }
    var levelDraft by remember(progress.level) { mutableStateOf(progress.level.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Progression",
            typography = AppTheme.typography.Heading.H500,
            color = AppTheme.colors.content,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.R700.shape)
                .background(AppTheme.colors.surface.color)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Current: Level ${progress.level} · $totalXp XP",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.content,
            )
            Text(
                text = "Sets XP directly — bypasses the ledger. Level snaps to that level's start XP.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
            ProgressionInputRow(
                label = "XP",
                draft = xpDraft,
                onDraftChange = { xpDraft = it },
                onApply = {
                    val parsed = xpDraft.toLongOrNull() ?: return@ProgressionInputRow
                    onSetTotalXp(parsed.coerceAtLeast(0L))
                },
                keyboardType = KeyboardType.Number,
            )
            ProgressionInputRow(
                label = "Level",
                draft = levelDraft,
                onDraftChange = { levelDraft = it },
                onApply = {
                    val parsed = levelDraft.toIntOrNull() ?: return@ProgressionInputRow
                    onSetTotalXp(xpAtStartOfLevel(parsed, levelCurve))
                },
                keyboardType = KeyboardType.Number,
            )
            Box(
                modifier = Modifier
                    .clip(Radii.R400.shape)
                    .background(AppTheme.colors.accentSecondarySubtle.color)
                    .clickable(onClick = onActivateXpBoost)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Grant + activate XP Boost (30m)",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.accentSecondary,
                )
            }
        }
    }
}

@Composable
private fun ProgressionInputRow(
    label: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    onApply: () -> Unit,
    keyboardType: KeyboardType,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
            modifier = Modifier.width(56.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(Radii.R400.shape)
                .background(AppTheme.colors.surfaceRaised.color)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                typographyToken = AppTheme.typography.Body.B500,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(Radii.R400.shape)
                .background(AppTheme.colors.accentPrimary.color)
                .clickable(onClick = onApply)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = "Apply",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onAccentPrimary,
            )
        }
    }
}

@Composable
private fun QaSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.replaceFirstChar { it.uppercase() },
            typography = AppTheme.typography.Heading.H500,
            color = AppTheme.colors.content,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.R700.shape)
                .background(AppTheme.colors.surface.color)
                .padding(vertical = 4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun QaRow(
    row: QaRowData,
    draft: String,
    onDraftChange: (String) -> Unit,
    onApply: (Any) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.content,
                )
                Text(
                    text = row.path,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                )
            }
            Text(
                text = typeLabelFor(row.default),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "current: ${row.currentValue}    default: ${row.default}",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
        )
        Spacer(modifier = Modifier.height(8.dp))

        when {
            row.default is Boolean -> {
                // Switch toggles immediately — no Apply button needed.
                val checked = (row.currentValue as? Boolean) ?: false
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (checked) "on" else "off",
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.content,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = checked,
                        onCheckedChange = { onApply(it) },
                    )
                }
            }
            row.allowedValues != null -> {
                // Enum-style values: render each option as a chip. Tapping a
                // chip applies the override immediately.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.allowedValues.forEach { option ->
                        val selected = option == row.currentValue
                        Box(
                            modifier = Modifier
                                .clip(Radii.Round.shape)
                                .background(
                                    if (selected) AppTheme.colors.accentPrimary.color
                                    else AppTheme.colors.surfaceRaised.color,
                                )
                                .clickable { onApply(option) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = option.toString(),
                                typography = AppTheme.typography.Body.B500,
                                color = if (selected) AppTheme.colors.onAccentPrimary
                                else AppTheme.colors.content,
                            )
                        }
                    }
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(Radii.R400.shape)
                            .background(AppTheme.colors.surfaceRaised.color)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = keyboardTypeFor(row.default),
                            ),
                            typographyToken = AppTheme.typography.Body.B500,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(Radii.R400.shape)
                            .background(AppTheme.colors.accentPrimary.color)
                            .clickable {
                                val parsed = parseValue(draft, row.default)
                                if (parsed != null) onApply(parsed)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "Apply",
                            typography = AppTheme.typography.Body.B500,
                            color = AppTheme.colors.onAccentPrimary,
                        )
                    }
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.border.color.copy(alpha = 0.3f)),
    )
}

private fun typeLabelFor(default: Any): String = when (default) {
    is Boolean -> "bool"
    is Int -> "int"
    is Long -> "long"
    is Double -> "double"
    is String -> "string"
    else -> default::class.simpleName ?: "?"
}

private data class QaRowData(
    val group: String,
    val name: String,
    val path: String,
    val default: Any,
    val currentValue: Any,
    val allowedValues: List<Any>?,
)

/**
 * Turns the injected [ConfiguredValue] set into displayable rows. Metadata
 * (name/path/default/allowedValues/group) comes off each value; the *current*
 * value is resolved against the streamed [configMap] so overrides applied this
 * session show up live. Sorted by group then name for a stable layout.
 */
private fun buildRows(
    configuredValues: Set<QaConfigValue>,
    configMap: AppConfigMap,
): List<QaRowData> = configuredValues
    .filterIsInstance<ConfiguredValue<*>>()
    .filter { it.showInQADashboard }
    .map { value ->
        QaRowData(
            group = value.group,
            name = value.name,
            path = value.path,
            default = value.default,
            currentValue = resolveCurrent(value, configMap),
            allowedValues = value.allowedValues,
        )
    }
    .sortedWith(compareBy({ it.group }, { it.name }))

private fun resolveCurrent(value: ConfiguredValue<*>, configMap: AppConfigMap): Any {
    return when (val d = value.default) {
        is Int -> configMap.value(@Suppress("UNCHECKED_CAST") (value as ConfiguredValue<Int>))
        is Long -> configMap.value(@Suppress("UNCHECKED_CAST") (value as ConfiguredValue<Long>))
        is Double -> configMap.value(@Suppress("UNCHECKED_CAST") (value as ConfiguredValue<Double>))
        is Boolean -> configMap.value(@Suppress("UNCHECKED_CAST") (value as ConfiguredValue<Boolean>))
        is String -> configMap.value(@Suppress("UNCHECKED_CAST") (value as ConfiguredValue<String>))
        else -> d
    }
}

private fun parseValue(draft: String, default: Any): Any? = when (default) {
    is Int -> draft.toIntOrNull()
    is Long -> draft.toLongOrNull()
    is Double -> draft.toDoubleOrNull()
    is Boolean -> when (draft.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }
    is String -> draft
    else -> null
}

private fun keyboardTypeFor(default: Any): KeyboardType = when (default) {
    is Int, is Long -> KeyboardType.Number
    is Double -> KeyboardType.Decimal
    else -> KeyboardType.Text
}

private class PreviewConfigOverrideRepository(
    private val initial: List<com.dangerfield.cards.libraries.config.ConfigOverride<Any>> = emptyList(),
) : com.dangerfield.cards.libraries.config.ConfigOverrideRepository {
    private val flow = kotlinx.coroutines.flow.MutableStateFlow(initial)
    override fun getOverrides(): List<com.dangerfield.cards.libraries.config.ConfigOverride<Any>> = flow.value
    override fun getOverridesFlow(): kotlinx.coroutines.flow.Flow<List<com.dangerfield.cards.libraries.config.ConfigOverride<Any>>> = flow
    override suspend fun addOverride(override: com.dangerfield.cards.libraries.config.ConfigOverride<Any>) {
        flow.value = flow.value.filter { it.path != override.path } + override
    }
    override suspend fun clearAll() {
        flow.value = emptyList()
    }
}

private class PreviewAppConfigMap(override val map: Map<String, *>) : AppConfigMap()

/** A representative spread of values (int, enum string, free string, flags)
 *  across two groups so the preview mirrors what the real injected set looks like. */
private fun previewConfiguredValues(map: AppConfigMap): Set<QaConfigValue> = setOf(
    object : IntConfigValue(map) {
        override val name = "Min supported version code"
        override val path = "upgrade.minSupportedVersionCode"
        override val default = 1
    },
    object : StringConfigValue(map) {
        override val name = "Maintenance mode"
        override val path = "upgrade.maintenanceMode"
        override val default = "off"
        override val allowedValues = listOf("off", "banner", "blocking")
    },
    object : StringConfigValue(map) {
        override val name = "Maintenance message"
        override val path = "upgrade.maintenanceMessage"
        override val default = "We're updating the servers, back in a moment."
    },
    object : FlagConfigValue(map) {
        override val name = "Google sign-in enabled"
        override val path = "identity.googleSignInEnabled"
        override val default = false
    },
    object : FlagConfigValue(map) {
        override val name = "Apple sign-in enabled"
        override val path = "identity.appleSignInEnabled"
        override val default = false
    },
)

@Preview
@Composable
private fun QaMenuScreenPreview_SignedIn() {
    val configMap = previewConfigMap()
    com.dangerfield.cards.libraries.ui.PreviewContent {
        QaMenuScreen(
            configStream = kotlinx.coroutines.flow.flowOf(configMap),
            initialConfig = configMap,
            configuredValues = previewConfiguredValues(configMap),
            overrideRepository = PreviewConfigOverrideRepository(),
            onBack = {},
            userId = "00000000-0000-4000-8000-000000000000",
            appVersion = "0.1.0 (247) · beta",
            backendEnv = "prod",
        )
    }
}

@Preview
@Composable
private fun QaMenuScreenPreview_UnresolvedIdentity() {
    val configMap = previewConfigMap()
    com.dangerfield.cards.libraries.ui.PreviewContent {
        QaMenuScreen(
            configStream = kotlinx.coroutines.flow.flowOf(configMap),
            initialConfig = configMap,
            configuredValues = previewConfiguredValues(configMap),
            overrideRepository = PreviewConfigOverrideRepository(),
            onBack = {},
            userId = null,
            appVersion = "0.1.0 (247) · debug",
            backendEnv = "dev",
            onOpenNetworkInspector = {},
        )
    }
}

@Preview
@Composable
private fun QaMenuScreenPreview_OverridesActive() {
    val configMap = PreviewAppConfigMap(
        map = mapOf(
            "upgrade" to mapOf(
                "minSupportedVersionCode" to 99,
                "maintenanceMode" to "banner",
                "maintenanceMessage" to "We're updating the servers...",
            ),
        ),
    )
    com.dangerfield.cards.libraries.ui.PreviewContent {
        QaMenuScreen(
            configStream = kotlinx.coroutines.flow.flowOf(configMap),
            initialConfig = configMap,
            configuredValues = previewConfiguredValues(configMap),
            overrideRepository = PreviewConfigOverrideRepository(),
            onBack = {},
            userId = "00000000-0000-4000-8000-000000000000",
            appVersion = "0.1.0 (247) · beta",
            backendEnv = "prod",
        )
    }
}

private fun previewConfigMap(): PreviewAppConfigMap = PreviewAppConfigMap(
    map = mapOf(
        "upgrade" to mapOf(
            "minSupportedVersionCode" to 1,
            "maintenanceMode" to "off",
            "maintenanceMessage" to "We're updating the servers...",
        ),
    ),
)
