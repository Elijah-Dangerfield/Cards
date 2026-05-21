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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlinx.coroutines.delay
import com.dangerfield.cards.features.upgrade.UpgradeConfig
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.ConfigOverride
import com.dangerfield.cards.libraries.config.ConfigOverrideRepository
import com.dangerfield.cards.libraries.config.ConfiguredValue
import com.dangerfield.cards.libraries.config.FeatureConfig
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.Switch
import com.dangerfield.cards.libraries.ui.components.text.BasicTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
fun QaMenuScreen(
    configStream: Flow<AppConfigMap>,
    initialConfig: AppConfigMap,
    overrideRepository: ConfigOverrideRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    userId: String? = null,
) {
    val scope = rememberCoroutineScope()
    val configMap by configStream.collectAsState(initial = initialConfig)

    val featureConfigs = remember(configMap) { knownFeatureConfigs(configMap) }
    val rows = remember(featureConfigs) { collectRows(featureConfigs) }

    val drafts = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(rows) {
        rows.forEach { row ->
            // Always sync drafts to live values so the editor reflects what's currently active.
            drafts[row.path] = row.currentValue.toString()
        }
    }

    Screen(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = AppTheme.colors.text.color,
                    )
                }
                Text(
                    text = "QA menu",
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.text,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = "Override any server-driven config value for this session. Cleared on uninstall.",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )

                UserIdBlock(userId = userId)

                Box(
                    modifier = Modifier
                        .clip(Radii.R400.shape)
                        .background(AppTheme.colors.surfaceSecondary.color)
                        .clickable {
                            scope.launch { overrideRepository.clearAll() }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Clear all overrides",
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.text,
                    )
                }

                featureConfigs.forEach { feature ->
                    val featureRows = rows.filter { it.featureName == feature.featureName }
                    if (featureRows.isEmpty()) return@forEach
                    QaSection(title = feature.featureName) {
                        featureRows.forEach { row ->
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserIdBlock(userId: String?) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val clickable = if (userId != null) {
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
            .background(AppTheme.colors.surfacePrimary.color)
            .then(clickable)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "User ID",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = userId ?: "— not signed in —",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfacePrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                userId == null -> "Identity not resolved yet — check IdentityRepository."
                copied -> "Copied"
                else -> "Long-press to copy"
            },
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
        )
    }
}

@Composable
private fun QaSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.replaceFirstChar { it.uppercase() },
            typography = AppTheme.typography.Heading.H500,
            color = AppTheme.colors.text,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.R700.shape)
                .background(AppTheme.colors.surfacePrimary.color)
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
                    color = AppTheme.colors.onSurfacePrimary,
                )
                Text(
                    text = row.path,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.onSurfaceSecondary,
                )
            }
            Text(
                text = typeLabelFor(row.default),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "current: ${row.currentValue}    default: ${row.default}",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
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
                        color = AppTheme.colors.onSurfacePrimary,
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
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) AppTheme.colors.accentPrimary.color
                                    else AppTheme.colors.surfaceSecondary.color,
                                )
                                .clickable { onApply(option) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = option.toString(),
                                typography = AppTheme.typography.Body.B500,
                                color = if (selected) AppTheme.colors.onAccentPrimary
                                else AppTheme.colors.onSurfacePrimary,
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
                            .background(AppTheme.colors.surfaceSecondary.color)
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
    val featureName: String,
    val name: String,
    val path: String,
    val default: Any,
    val currentValue: Any,
    val allowedValues: List<Any>?,
)

private fun knownFeatureConfigs(configMap: AppConfigMap): List<FeatureConfig> = listOf(
    UpgradeConfig(configMap),
)

private fun collectRows(featureConfigs: List<FeatureConfig>): List<QaRowData> {
    // Touch each property so FeatureConfig.values populates.
    featureConfigs.forEach { fc ->
        if (fc is UpgradeConfig) {
            fc.minSupportedVersionCode
            fc.maintenanceMode
            fc.maintenanceMessage
        }
    }
    return featureConfigs.flatMap { fc ->
        fc.values.map { value ->
            QaRowData(
                featureName = fc.featureName,
                name = value.name,
                path = value.path,
                default = value.default,
                currentValue = resolveCurrent(value, fc.configMap),
                allowedValues = value.allowedValues,
            )
        }
    }
}

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

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun QaMenuScreenPreview_SignedIn() {
    val configMap = previewConfigMap()
    com.dangerfield.cards.libraries.ui.PreviewContent {
        QaMenuScreen(
            configStream = kotlinx.coroutines.flow.flowOf(configMap),
            initialConfig = configMap,
            overrideRepository = PreviewConfigOverrideRepository(),
            onBack = {},
            userId = "00000000-0000-4000-8000-000000000000",
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun QaMenuScreenPreview_UnresolvedIdentity() {
    val configMap = previewConfigMap()
    com.dangerfield.cards.libraries.ui.PreviewContent {
        QaMenuScreen(
            configStream = kotlinx.coroutines.flow.flowOf(configMap),
            initialConfig = configMap,
            overrideRepository = PreviewConfigOverrideRepository(),
            onBack = {},
            userId = null,
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
