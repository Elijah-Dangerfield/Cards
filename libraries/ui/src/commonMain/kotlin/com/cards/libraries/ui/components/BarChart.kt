package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.thenIf
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Represents one entry (or bar) in the chart. The [payload] allows callers to retain the
 * domain-specific object that produced this entry so selection/click handling can round-trip
 * back to their feature logic without building additional lookup tables.
 */
data class BarChartEntry<ID, Payload>(
    val id: ID,
    val value: Float,
    val label: String? = null,
    val payload: Payload? = null
)

/**
 * Defines the axis bounds, tick marks, and string formatting for the chart. Supply a custom
 * instance when callers need full control over tick placement or units.
 */
data class BarChartAxis(
    val maxValue: Float,
    val ticks: List<Float>,
    val labelFormatter: (Float) -> String
) {
    init {
        require(maxValue > 0f) { "Axis maxValue must be positive" }
        require(ticks.isNotEmpty()) { "Axis must include at least one tick" }
    }
}

/**
 * Core, highly-configurable bar chart building block. Slots are exposed for axis, labels, bars,
 * and guide lines so that features can tailor both visuals and semantics without reimplementing
 * layout logic.
 */
@Composable
fun <ID, Payload> BarChart(
    entries: List<BarChartEntry<ID, Payload>>,
    modifier: Modifier = Modifier,
    axis: BarChartAxis = remember(entries) {
        BarChartAxisDefaults.niceScale(entries.maxValueOrZero())
    },
    chartHeight: Dp = 180.dp,
    spacing: Dp = Dimension.D500,
    minBarHeightFraction: Float = 0.02f,
    selectedEntryId: ID? = null,
    onEntryClick: ((BarChartEntry<ID, Payload>) -> Unit)? = null,
    drawGuides: Boolean = true,
    guideColor: Color = AppTheme.colors.surfaceRaised.color.copy(alpha = 0.4f),
    axisContent: @Composable (BarChartAxis) -> Unit = { BarChartDefaults.Axis(it) },
    labelsContent: @Composable (
        leadingOffset: Dp,
        entries: List<BarChartEntry<ID, Payload>>,
        selectedEntryId: ID?
    ) -> Unit = { leadingOffset, items, selectedId ->
        BarChartDefaults.Labels(leadingOffset, items, selectedId)
    },
    barContent: (@Composable (
        entry: BarChartEntry<ID, Payload>,
        isSelected: Boolean,
        valueFraction: Float,
        visualFraction: Float,
        modifier: Modifier
    ) -> Unit)? = null,
    barShape: Shape = Radii.R500.shape,
    barColors: @Composable (BarChartEntry<ID, Payload>, Boolean) -> Color = { _, isSelected ->
        if (isSelected) AppTheme.colors.accentPrimary.color else AppTheme.colors.surfaceDisabled.color
    }
) {
    if (entries.isEmpty()) return

    val density = LocalDensity.current
    var axisWidthPx by remember { mutableStateOf(0) }
    val axisWidthDp = with(density) { axisWidthPx.toDp() }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .height(chartHeight)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .onGloballyPositioned { coordinates ->
                        axisWidthPx = coordinates.size.width
                    }
            ) {
                axisContent(axis)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                if (drawGuides) {
                    BarChartGuides(axis = axis, guideColor = guideColor)
                }

                BarChartBars(
                    entries = entries,
                    axis = axis,
                    spacing = spacing,
                    minBarHeightFraction = minBarHeightFraction,
                    selectedEntryId = selectedEntryId,
                    onEntryClick = onEntryClick,
                    barContent = barContent,
                    barShape = barShape,
                    barColors = barColors
                )
            }
        }

        VerticalSpacerD500()

        labelsContent(axisWidthDp, entries, selectedEntryId)
    }
}

@Composable
private fun <ID, Payload> BarChartBars(
    entries: List<BarChartEntry<ID, Payload>>,
    axis: BarChartAxis,
    spacing: Dp,
    minBarHeightFraction: Float,
    selectedEntryId: ID?,
    onEntryClick: ((BarChartEntry<ID, Payload>) -> Unit)?,
    barContent: (@Composable (
        entry: BarChartEntry<ID, Payload>,
        isSelected: Boolean,
        valueFraction: Float,
        visualFraction: Float,
        modifier: Modifier
    ) -> Unit)?,
    barShape: Shape,
    barColors: @Composable (BarChartEntry<ID, Payload>, Boolean) -> Color
) {
    val resolvedBarContent = barContent ?: { entry, isSelected, _, _, sizedModifier ->
        Box(
            modifier = sizedModifier
                .clip(barShape)
                .background(barColors(entry, isSelected))
        )
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.Bottom
    ) {
        val axisMax = axis.maxValue.coerceAtLeast(1f)
        entries.forEach { entry ->
            val rawFraction = (entry.value / axisMax).coerceIn(0f, 1f)
            val visualFraction = if (entry.value <= 0f) {
                0f
            } else {
                rawFraction.coerceAtLeast(minBarHeightFraction)
            }
            val isSelected = selectedEntryId != null && entry.id == selectedEntryId

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.BottomCenter
            ) {
                val sizedModifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(visualFraction)
                    .thenIf(onEntryClick != null) {
                        clickable { onEntryClick?.invoke(entry) }
                    }

                resolvedBarContent(entry, isSelected, rawFraction, visualFraction, sizedModifier)
            }
        }
    }
}

@Composable
private fun BarChartGuides(
    axis: BarChartAxis,
    guideColor: Color
) {
    val axisMax = axis.maxValue.coerceAtLeast(1f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val chartHeight = size.height
        val guideValues = buildSet {
            axis.ticks
                .filter { it in 0f..axisMax }
                .forEach { add(it) }
            add(0f)
        }.sorted()

        guideValues.forEach { value ->
            if (value >= 0f && value <= axisMax) {
                val fraction = value / axisMax
                val y = chartHeight - (chartHeight * fraction)
                drawLine(
                    color = guideColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
    }
}

object BarChartDefaults {
    @Composable
    fun Axis(axis: BarChartAxis) {
        if (axis.ticks.isEmpty()) return

        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            axis.ticks.sortedDescending().forEach { value ->
                Text(
                    modifier = Modifier.padding(end = Dimension.D200),
                    text = axis.labelFormatter(value),
                    typography = AppTheme.typography.Caption.C300,
                    color = AppTheme.colors.contentDisabled
                )
            }
        }
    }

    @Composable
    fun <ID, Payload> Labels(
        leadingOffset: Dp,
        entries: List<BarChartEntry<ID, Payload>>,
        selectedEntryId: ID?
    ) {
        if (entries.isEmpty()) return

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Spacer(Modifier.width(leadingOffset))

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Dimension.D500)
            ) {
                entries.forEach { entry ->
                    val label = entry.label ?: return@forEach
                    val isSelected = selectedEntryId != null && entry.id == selectedEntryId
                    val color = if (isSelected) {
                        AppTheme.colors.content
                    } else {
                        AppTheme.colors.contentDisabled
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = label,
                            typography = AppTheme.typography.Caption.C400.apply {
                                if (isSelected) this.Bold else this.Light
                            },
                            color = color
                        )
                    }
                }
            }
        }
    }
}

object BarChartAxisDefaults {
    fun niceScale(
        maxValue: Float,
        maxTicks: Int = 6,
        labelFormatter: (Float) -> String = { value ->
            if (value % 1f == 0f) value.roundToInt().toString() else value.toString()
        }
    ): BarChartAxis {
        val safeMax = maxValue.coerceAtLeast(1f)
        val clampedTicks = maxTicks.coerceAtLeast(2)
        val step = niceStep(safeMax, clampedTicks)
        val axisMax = ceil(safeMax / step) * step
        val ticks = buildList {
            var value = 0f
            while (value <= axisMax + 0.0001f) {
                add(value)
                value += step
            }
        }
        return BarChartAxis(
            maxValue = axisMax,
            ticks = ticks,
            labelFormatter = labelFormatter
        )
    }

    private fun niceStep(maxValue: Float, maxTicks: Int): Float {
        val rawStep = maxValue / (maxTicks - 1)
        if (rawStep <= 0f) return 1f
        val magnitude = 10.0.pow(floor(log10(rawStep.toDouble())))
        val residual = rawStep / magnitude
        val niceResidual = when {
            residual <= 1.0 -> 1.0
            residual <= 2.0 -> 2.0
            residual <= 5.0 -> 5.0
            else -> 10.0
        }
        return (niceResidual * magnitude).toFloat()
    }
}

private fun <ID, Payload> List<BarChartEntry<ID, Payload>>.maxValueOrZero(): Float =
    this.maxOfOrNull { it.value } ?: 0f

// ---- Previews ------------------------------------------------------------------------------

@Preview(name = "BarChart – Auto axis", widthDp = 360, heightDp = 280)
@Composable
private fun BarChartPreviewAutoAxis() {
    val entries = previewDailyUsageEntries()
    PreviewContent {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surface.color)
                .padding(Dimension.D500)
        ) {
            BarChart(
                entries = entries,
                selectedEntryId = entries[2].id
            )
        }
    }
}

@Preview(name = "BarChart – Custom axis & labels", widthDp = 360, heightDp = 300)
@Composable
private fun BarChartPreviewCustomAxisAndLabels() {
    val entries = previewBudgetEntries()
    val axis = BarChartAxis(
        maxValue = 150f,
        ticks = listOf(0f, 25f, 50f, 75f, 100f, 125f, 150f)
    ) { value ->
        "$${value.roundToInt()}"
    }

    PreviewContent {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surfaceRaised.color)
                .padding(Dimension.D500)
        ) {
            BarChart(
                entries = entries,
                axis = axis,
                selectedEntryId = entries.first().id,
                axisContent = { customAxis ->
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        customAxis.ticks.sortedDescending().forEach { tick ->
                            Text(
                                text = "${customAxis.labelFormatter(tick)} USD",
                                typography = AppTheme.typography.Caption.C300,
                                color = AppTheme.colors.contentDisabled
                            )
                        }
                    }
                },
                labelsContent = { leadingOffset, items, _ ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Spacer(Modifier.width(leadingOffset))

                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(Dimension.D500)
                        ) {
                            items.forEach { entry ->
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = entry.label.orEmpty(),
                                        typography = AppTheme.typography.Caption.C400,
                                        color = AppTheme.colors.contentDisabled
                                    )

                                    VerticalSpacerD200()

                                    Text(
                                        text = "$${entry.value.roundToInt()}",
                                        typography = AppTheme.typography.Caption.C400,
                                        color = AppTheme.colors.content
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    }
}

@Preview(name = "BarChart – Custom bars", widthDp = 360, heightDp = 300)
@Composable
private fun BarChartPreviewCustomBars() {
    val entries = previewFocusEntries()

    PreviewContent {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surface.color)
                .padding(Dimension.D500)
        ) {
            BarChart(
                entries = entries,
                chartHeight = 220.dp,
                spacing = Dimension.D200,
                drawGuides = false,
                selectedEntryId = entries.last().id,
                barContent = { entry, isSelected, _, _, modifier ->
                    val gradient = if (isSelected) {
                        Brush.verticalGradient(
                            colors = listOf(
                                AppTheme.colors.accentPrimary.color,
                                AppTheme.colors.accentPrimary.color.copy(alpha = 0.4f)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                AppTheme.colors.surfaceRaised.color,
                                AppTheme.colors.surfaceRaised.color.copy(alpha = 0.2f)
                            )
                        )
                    }

                    Box(
                        modifier = modifier
                            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                            .background(gradient),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            modifier = Modifier.padding(top = Dimension.D200),
                            text = "${entry.value.roundToInt()}m",
                            typography = AppTheme.typography.Caption.C300,
                            color = AppTheme.colors.content
                        )
                    }
                },
                labelsContent = { leadingOffset, items, selectedId ->
                    BarChartDefaults.Labels(leadingOffset, items, selectedId)
                }
            )
        }
    }
}

@Preview(name = "BarChart – Dense dataset", widthDp = 360, heightDp = 280)
@Composable
private fun BarChartPreviewDenseData() {
    val entries = previewDenseEntries()

    PreviewContent  {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surface.color)
                .padding(Dimension.D500)
        ) {
            BarChart(
                entries = entries,
                spacing = Dimension.D200,
                chartHeight = 200.dp,
                minBarHeightFraction = 0f,
                barShape = RoundedCornerShape(6.dp),
                axisContent = {},
                labelsContent = { leadingOffset, items, _ ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Spacer(Modifier.width(leadingOffset))

                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(Dimension.D200)
                        ) {
                            items.forEach { entry ->
                                Text(
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f),
                                    text = entry.label.orEmpty(),
                                    typography = AppTheme.typography.Caption.C300,
                                    color = AppTheme.colors.contentDisabled
                                )
                            }
                        }
                    }
                },
                barColors = { entry, _ ->
                    if (entry.value >= 60f) {
                        AppTheme.colors.accentPrimary.color
                    } else {
                        AppTheme.colors.surfaceDisabled.color
                    }
                }
            )
        }
    }
}

private fun previewDailyUsageEntries(): List<BarChartEntry<String, Nothing>> = previewEntries(
    "Mon" to 45f,
    "Tue" to 73f,
    "Wed" to 38f,
    "Thu" to 110f,
    "Fri" to 68f,
    "Sat" to 95f,
    "Sun" to 54f
)

private fun previewBudgetEntries(): List<BarChartEntry<String, Nothing>> = previewEntries(
    "Design" to 120f,
    "Engineering" to 150f,
    "Research" to 80f,
    "Ops" to 55f
)

private fun previewFocusEntries(): List<BarChartEntry<String, Nothing>> = previewEntries(
    "Deep Work" to 180f,
    "Meetings" to 75f,
    "Email" to 30f,
    "Learning" to 95f
)

private fun previewDenseEntries(): List<BarChartEntry<String, Nothing>> = previewEntries(
    "W1" to 10f,
    "W2" to 60f,
    "W3" to 0f,
    "W4" to 35f,
    "W5" to 90f,
    "W6" to 75f,
    "W7" to 20f,
    "W8" to 65f,
    "W9" to 40f,
    "W10" to 80f
)

private fun previewEntries(vararg data: Pair<String, Float>): List<BarChartEntry<String, Nothing>> =
    data.mapIndexed { index, (label, value) ->
        BarChartEntry(
            id = "${label.lowercase()}-$index",
            value = value,
            label = label
        )
    }
