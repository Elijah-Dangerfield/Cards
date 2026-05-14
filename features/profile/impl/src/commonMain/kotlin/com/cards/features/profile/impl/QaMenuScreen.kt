package com.dangerfield.cards.features.profile.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.features.upgrade.UpgradeConfig
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.ConfigOverride
import com.dangerfield.cards.libraries.config.ConfigOverrideRepository
import com.dangerfield.cards.libraries.config.ConfiguredValue
import com.dangerfield.cards.libraries.config.FeatureConfig
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.text.BasicTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import kotlinx.coroutines.launch

@Composable
fun QaMenuScreen(
    configMap: AppConfigMap,
    overrideRepository: ConfigOverrideRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    val featureConfigs = remember(configMap) { knownFeatureConfigs(configMap) }
    val rows = remember(featureConfigs) { collectRows(featureConfigs) }

    val drafts = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(rows) {
        rows.forEach { row ->
            if (row.path !in drafts) drafts[row.path] = row.currentValue.toString()
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

                featureConfigs.forEach { feature ->
                    val featureRows = rows.filter { it.featureName == feature.featureName }
                    if (featureRows.isEmpty()) return@forEach
                    QaSection(title = feature.featureName) {
                        featureRows.forEach { row ->
                            QaRow(
                                row = row,
                                draft = drafts[row.path] ?: row.currentValue.toString(),
                                onDraftChange = { drafts[row.path] = it },
                                onApply = {
                                    val parsed = parseValue(drafts[row.path] ?: "", row.default)
                                    if (parsed != null) {
                                        scope.launch {
                                            overrideRepository.addOverride(ConfigOverride(row.path, parsed))
                                        }
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
                .clip(RoundedCornerShape(16.dp))
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
    onApply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
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
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "current: ${row.currentValue}    default: ${row.default}",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
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
                    .clip(RoundedCornerShape(10.dp))
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.border.color.copy(alpha = 0.3f)),
    )
}

private data class QaRowData(
    val featureName: String,
    val name: String,
    val path: String,
    val default: Any,
    val currentValue: Any,
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
