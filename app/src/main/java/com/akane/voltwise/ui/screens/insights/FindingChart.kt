package com.akane.voltwise.ui.screens.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.akane.voltwise.R
import com.akane.voltwise.battery.insights.model.Evidence
import com.akane.voltwise.battery.insights.model.FindingType
import com.akane.voltwise.battery.insights.model.Metric
import com.akane.voltwise.battery.insights.model.MetricUnit
import com.akane.voltwise.battery.insights.model.SeriesPoint
import com.akane.voltwise.ui.components.chart.CHART_TEXT_CACHE_SIZE
import com.akane.voltwise.ui.components.chart.ChartModel
import com.akane.voltwise.ui.components.chart.ChartReference
import com.akane.voltwise.ui.components.chart.ChartSeries
import com.akane.voltwise.ui.components.chart.LegendItem
import com.akane.voltwise.ui.components.chart.LegendSwatch
import com.akane.voltwise.ui.components.chart.PlotLayout
import com.akane.voltwise.ui.components.chart.SeriesStyle
import com.akane.voltwise.ui.components.chart.TimePoint
import com.akane.voltwise.ui.components.chart.TimeSeriesChartDefaults
import com.akane.voltwise.ui.components.chart.buildSeriesDrawing
import com.akane.voltwise.ui.components.chart.drawRingedDot
import com.akane.voltwise.ui.components.chart.drawSeriesLine
import com.akane.voltwise.ui.components.chart.readoutGranularity
import com.akane.voltwise.ui.components.chart.rememberTimeAxisFormatter
import com.akane.voltwise.ui.theme.chartColors
import com.akane.voltwise.ui.theme.numericLabel
import com.akane.voltwise.ui.theme.spacing

// Drawing sizes, matching TimeSeriesChart's marks (2 dp line, ringed dots) with a larger dot for emphasis.
private val LineWidth = 2.dp
private val GridWidth = 1.dp
private val DotRadius = 3.dp
private val EmphasisRadius = 5.dp
private val RingWidth = 2.dp
private val DashLength = 4.dp
private val DotKeySize = 10.dp

/** The usual-range band: a quiet wash of the line's own neutral, behind the grid's weight but clearly a region. */
private const val BAND_ALPHA = 0.16f

/** Above this many readings the ordinary ones get no dot of their own; only emphasised ones keep theirs. */
private const val MAX_PLAIN_DOTS = 24

/**
 * One reading as the chart draws it: [value] and its usual range in axis units (see [axisScale]), and whether it's
 * one to emphasise (outside its usual range; without any range, the latest reading).
 */
@Immutable
internal data class FindingChartPoint(val atMs: Long, val value: Double, val low: Double?, val high: Double?, val emphasised: Boolean)

/** What a finding's chart plots: [metric] names the axis caption and the TalkBack summary; [usual] is its usual level. */
internal data class ChartMeasure(val metric: Metric, val usual: Double?)

/**
 * The series measures the finding's first evidence metric, against that evidence's baseline, except a capacity decline:
 * its series holds the capacity estimates in mAh (ChargingHealth) while its evidence is the change per year.
 */
internal fun chartMeasure(type: FindingType, evidence: List<Evidence>): ChartMeasure? = when (type) {
    FindingType.HEALTH_DECLINE -> ChartMeasure(Metric.CAPACITY_MAH, usual = null)
    else -> evidence.firstOrNull()?.let { ChartMeasure(it.metric, it.baseline) }
}

/** The readings in time order, scaled to axis units; non-finite readings are dropped rather than plotted. */
internal fun chartPoints(series: List<SeriesPoint>, unit: MetricUnit): List<FindingChartPoint> {
    val scale = unit.axisScale()
    val sorted = series.filter { it.value.isFinite() }.sortedBy { it.atMs }
    val banded = sorted.any { it.baselineLow != null && it.baselineHigh != null }
    return sorted.mapIndexed { i, point ->
        val low = point.baselineLow?.takeIf { it.isFinite() && point.baselineHigh?.isFinite() == true }
        val high = point.baselineHigh?.takeIf { low != null }
        val outside = low != null && high != null && (point.value < low || point.value > high)
        FindingChartPoint(
            point.atMs, point.value * scale, low?.times(scale), high?.times(scale),
            emphasised = if (banded) outside else i == sorted.lastIndex,
        )
    }
}

/** Display units per engine unit on the axis: minutes for ms/h, hours for ms, MB for bytes, percent for shares. */
internal fun MetricUnit.axisScale(): Double = when (this) {
    MetricUnit.MS_PER_H -> 1.0 / 60_000.0
    MetricUnit.MS -> 1.0 / 3_600_000.0
    MetricUnit.BYTES_PER_H -> 1.0 / 1_000_000.0
    MetricUnit.SHARE -> 100.0
    else -> 1.0
}

/** The caption above the value axis, in the units [axisScale] converts to. */
@Composable
private fun axisCaption(unit: MetricUnit): String = when (unit) {
    MetricUnit.MAH_PER_H -> stringResource(R.string.insights_unit_mah_per_h)
    MetricUnit.COUNT_PER_H -> stringResource(R.string.insights_unit_per_h, "")
    MetricUnit.MS_PER_H -> stringResource(R.string.insights_unit_min_per_h)
    MetricUnit.BYTES_PER_H -> stringResource(R.string.insights_unit_mb_per_h)
    MetricUnit.SHARE, MetricUnit.PCT -> PERCENT
    MetricUnit.RATIO -> stringResource(R.string.insights_ratio, "")
    MetricUnit.PCT_PER_H -> stringResource(R.string.insights_unit_per_h, PERCENT)
    MetricUnit.CELSIUS -> stringResource(R.string.insights_unit_celsius)
    MetricUnit.MAH -> stringResource(R.string.now_unit_mah)
    MetricUnit.MS -> stringResource(R.string.finding_unit_hours)
    MetricUnit.COUNT -> ""
    MetricUnit.PCT_PER_YEAR -> stringResource(R.string.insights_unit_per_year, PERCENT)
}

private const val PERCENT = "%"

/**
 * A finding's measurements over time against what is usual for it: the usual range as a tonal band where the engine
 * gives one (else a dashed "Usual" line at [usual]), the readings as a line, and the readings that stand out as
 * larger dots in the drain color (outside the band; without a band, the latest). Static: one value axis, no scrub.
 * TalkBack reads one summary: range, count, latest reading, the usual range and how many fall outside it.
 *
 * @param label what is measured ("Drain"), for the summary.
 * @param usual the evidence baseline in engine units, drawn only when no reading carries a band.
 */
@Composable
internal fun FindingChart(
    series: List<SeriesPoint>,
    unit: MetricUnit,
    label: String,
    usual: Double?,
    modifier: Modifier = Modifier,
) {
    val points = remember(series, unit) { chartPoints(series, unit) }
    if (points.isEmpty()) return
    val colors = MaterialTheme.chartColors
    val bandColor = colors.level.copy(alpha = BAND_ALPHA)
    val banded = points.any { it.low != null }
    val scale = unit.axisScale()
    val usualLabel = stringResource(R.string.finding_chart_usual)
    val caption = axisCaption(unit)
    val references = remember(banded, usual, scale, usualLabel) {
        if (!banded && usual != null && usual.isFinite()) listOf(ChartReference(usual * scale, usualLabel)) else emptyList()
    }
    val chartSeries = remember(points, caption, colors.level) {
        ChartSeries(
            label = label,
            points = points.map { TimePoint(it.atMs, it.value) },
            style = SeriesStyle.Solid(colors.level),
            unit = caption,
            axisMin = points.mapNotNull { it.low }.minOrNull(),
            axisMax = points.mapNotNull { it.high }.maxOrNull(),
        )
    }
    val model = remember(chartSeries, references) { ChartModel.of(listOf(chartSeries), null, references) }
    val summary = chartSummary(points, unit, label, usual.takeIf { !banded })
    val spacing = MaterialTheme.spacing

    Column(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = summary },
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            if (banded) LegendItem(stringResource(R.string.finding_chart_usual_range), key = { LegendSwatch(bandColor) })
            val emphasis = stringResource(if (banded) R.string.finding_chart_outside else R.string.finding_chart_latest)
            LegendItem(emphasis, key = { Box(Modifier.size(DotKeySize).background(colors.drain, CircleShape)) })
        }
        Box(Modifier.fillMaxWidth().height(TimeSeriesChartDefaults.Height)) {
            FindingPlot(points, chartSeries, model, references, bandColor)
        }
    }
}

@Composable
private fun FindingPlot(
    points: List<FindingChartPoint>,
    series: ChartSeries,
    model: ChartModel,
    references: List<ChartReference>,
    bandColor: Color,
) {
    val colors = MaterialTheme.chartColors
    val labelStyle = MaterialTheme.typography.numericLabel.copy(color = colors.axisLabel)
    val textMeasurer = rememberTextMeasurer(cacheSize = CHART_TEXT_CACHE_SIZE)
    val density = LocalDensity.current
    val timeFormatter = rememberTimeAxisFormatter()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val canvas = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val layout = remember(canvas, model, references, labelStyle, timeFormatter, density) {
            PlotLayout.measure(
                density, canvas, model, references, textMeasurer, labelStyle, timeFormatter,
                edgeInset = EmphasisRadius + RingWidth,
                endPadding = EmphasisRadius + RingWidth,
            )
        }
        Spacer(
            Modifier
                .fillMaxSize()
                // The ringed dots clear a gap around themselves, which needs an offscreen layer.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    if (!layout.hasRoom) return@drawWithCache onDrawBehind { }
                    val xMap = layout.xMap
                    val yMap = layout.yMaps[0]
                    val band = bandPath(points, xMap::x, yMap::y, EmphasisRadius.toPx())
                    val drawing = buildSeriesDrawing(series.points, 0, series.points.lastIndex, series.style, xMap, yMap, null, allDots = false)
                    val stroke = Stroke(LineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val gridWidth = GridWidth.toPx()
                    val dot = DotRadius.toPx()
                    val emphasis = EmphasisRadius.toPx()
                    val ring = RingWidth.toPx()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(DashLength.toPx(), DashLength.toPx()))
                    val plainDots = points.size <= MAX_PLAIN_DOTS
                    val ticks = layout.ticks[0]
                    val gap = layout.gap
                    onDrawBehind {
                        drawPath(band, bandColor)
                        for (i in 0 until ticks.count) {
                            val y = yMap.y(ticks.valueAt(i))
                            drawLine(colors.grid, Offset(layout.left, y), Offset(layout.right, y), gridWidth)
                        }
                        val labels = layout.tickLabels[0]
                        for (i in labels.indices) {
                            val label = labels[i]
                            val y = yMap.y(ticks.valueAt(i)) - label.size.height / 2
                            drawText(label, topLeft = Offset(layout.left - gap - label.size.width, y))
                        }
                        layout.unitLabels[0]?.let { unit -> drawText(unit, topLeft = Offset(layout.left - gap - unit.size.width, 0f)) }
                        for (i in layout.timeLabels.indices) {
                            drawText(layout.timeLabels[i], topLeft = Offset(layout.timeLabelLefts[i], layout.timeLabelTop))
                        }
                        for (i in references.indices) {
                            val y = layout.referenceYs[i]
                            drawLine(colors.axisLabel, Offset(layout.left, y), Offset(layout.right, y), gridWidth, pathEffect = dash)
                            val label = layout.referenceLabels[i]
                            val above = y - gap / 2 - label.size.height
                            drawText(label, topLeft = Offset(layout.right - label.size.width, if (above >= layout.top) above else y + gap / 2))
                        }
                        drawSeriesLine(drawing, stroke)
                        for (point in points) {
                            val center = Offset(xMap.x(point.atMs), yMap.y(point.value))
                            when {
                                point.emphasised -> drawRingedDot(colors.drain, center, emphasis, ring)
                                plainDots || points.size == 1 -> drawRingedDot(colors.level, center, dot, ring)
                            }
                        }
                    }
                },
        )
    }
}

/**
 * The usual-range band: each run of consecutive banded readings becomes one closed shape (upper edge forward, lower
 * edge back); a lone banded reading becomes a bar [halfWidth] each side of it.
 */
private fun bandPath(points: List<FindingChartPoint>, x: (Long) -> Float, y: (Double) -> Float, halfWidth: Float): Path {
    val path = Path()
    var start = 0
    while (start < points.size) {
        if (points[start].low == null) {
            start++
            continue
        }
        var end = start
        while (end + 1 < points.size && points[end + 1].low != null) end++
        val run = points.subList(start, end + 1)
        if (run.size == 1) {
            val p = run.single()
            val cx = x(p.atMs)
            path.addRect(Rect(cx - halfWidth, y(p.high ?: p.value), cx + halfWidth, y(p.low ?: p.value)))
        } else {
            run.forEachIndexed { i, p ->
                val px = x(p.atMs)
                val py = y(p.high ?: p.value)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            for (i in run.indices.reversed()) path.lineTo(x(run[i].atMs), y(run[i].low ?: run[i].value))
            path.close()
        }
        start = end + 1
    }
    return path
}

/** "Drain from Oct 2 to Oct 9: 6 readings, latest 41.6 mAh/h. Usual range 9 to 17 mAh/h. 2 readings are outside it." */
@Composable
private fun chartSummary(points: List<FindingChartPoint>, unit: MetricUnit, label: String, usual: Double?): String {
    val formatter = rememberTimeAxisFormatter()
    val first = points.first()
    val last = points.last()
    val granularity = readoutGranularity(last.atMs - first.atMs)
    val scale = unit.axisScale()
    val parts = mutableListOf(
        pluralStringResource(
            R.plurals.finding_chart_summary, points.size,
            label, formatter.format(first.atMs, granularity), formatter.format(last.atMs, granularity), points.size,
            metricValueText(last.value / scale, unit),
        ),
    )
    val lows = points.mapNotNull { it.low }
    val highs = points.mapNotNull { it.high }
    if (lows.isNotEmpty() && highs.isNotEmpty()) {
        parts += stringResource(
            R.string.finding_chart_summary_band,
            metricValueText(lows.min() / scale, unit),
            metricValueText(highs.max() / scale, unit),
        )
        val outside = points.count { it.emphasised }
        parts += pluralStringResource(R.plurals.finding_chart_summary_outside, outside, outside)
    } else if (usual != null && usual.isFinite()) {
        parts += stringResource(R.string.finding_chart_summary_usual, metricValueText(usual, unit))
    }
    return parts.joinToString(" ")
}
