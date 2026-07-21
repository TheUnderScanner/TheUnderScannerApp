package com.underscanner.theunderscannerapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===========================================================================
// Health charts for a finished scan — a "fake landscape" page.
//
// The activity stays locked to portrait and the whole content is rotated 90
// degrees clockwise, so the user turns the phone counter-clockwise to read it
// and the time axis gets the screen's long edge.
//
// The rotated content MUST be laid out with requiredSize(), not size(): plain
// size() is still bounded by the parent's (portrait) constraints, so the swapped
// landscape dimensions get coerced straight back and the chart renders in a
// portrait-shaped box. requiredSize ignores the incoming constraints.
//
// One chart fills the screen at a time; dragging pages between them. Because the
// container is rotated, gestures rotate with it — once the phone is turned, a
// horizontal swipe reads as horizontal.
// ===========================================================================

/** Line colors, in assignment order. Chosen to stay distinguishable on both themes. */
private val SeriesColors = listOf(
    Color(0xFF4FC3F7),   // blue
    Color(0xFFFFB74D),   // amber
    Color(0xFFE57373),   // red
    Color(0xFF81C784),   // green
    Color(0xFFBA68C8),   // purple
)

/**
 * One line on a chart. [transform] adapts a raw logged value to the chart's axis
 * (e.g. RAM in MB rendered as a percentage of total).
 */
private data class ChartSeries(
    val key: String,
    val label: String,
    val transform: (Float) -> Float = { it }
)

/** One full-screen page. */
private data class ChartSpec(
    val title: String,
    val unit: String,
    val series: List<ChartSeries>,
    val yRange: ClosedFloatingPointRange<Float>? = null,
    val bands: List<ClosedFloatingPointRange<Float>> = emptyList(),
    val footer: String? = null
)

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun formatT(seconds: Float): String {
    val s = seconds.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun HealthChartScreen(
    scanName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Lock to portrait for the lifetime of this screen; restore whatever was set before.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var log by remember { mutableStateOf<HealthLog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(scanName) {
        val file = LocalScanStorage.healthFile(context, scanName)
        withContext(Dispatchers.IO) { runCatching { HealthLog.parse(file.readText()) } }.fold(
            onSuccess = { if (it.isEmpty) error = "Journal vide." else log = it },
            onFailure = { error = "Journal illisible." }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Swap the screen's dimensions, force that size past the portrait constraints,
        // then rotate a quarter turn clockwise. The result fills the physical screen.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .requiredSize(width = maxHeight, height = maxWidth)
                .rotate(90f)
        ) {
            when {
                error != null -> CenteredMessage(error!!, onBack)
                log == null -> CenteredMessage("Chargement…", onBack)
                else -> ChartPager(log = log!!, scanName = scanName, onBack = onBack)
            }
        }
    }
}

@Composable
private fun CenteredMessage(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBack) { Text("Retour") }
        }
    }
}

@Composable
private fun ChartPager(log: HealthLog, scanName: String, onBack: () -> Unit) {
    val memTotal = log.static.memTotalMb?.takeIf { it > 0 }?.toFloat()
    val cpuMax = log.static.cpuMaxMhz?.takeIf { it > 0 }?.toFloat()

    // Build every candidate page, then drop the ones this log has no data for.
    val specs = remember(log) {
        listOf(
            ChartSpec(
                title = "Température",
                unit = "°C",
                series = listOf(
                    ChartSeries("t_tj", "Jonction"),
                    ChartSeries("t_cpu", "CPU"),
                    ChartSeries("t_gpu", "GPU"),
                    ChartSeries("t_soc", "SoC"),
                )
            ),
            ChartSpec(
                title = "Charge",
                unit = "%",
                yRange = 0f..100f,
                // The CPU clock as a fraction of its ceiling belongs here: on one 0-100
                // axis next to load, a throttle shows up as the clock line dropping.
                series = listOfNotNull(
                    ChartSeries("cpu", "CPU"),
                    ChartSeries("gpu", "GPU"),
                    memTotal?.let { total -> ChartSeries("mem", "RAM") { it / total * 100f } },
                    cpuMax?.let { max -> ChartSeries("cpu_f", "Fréq. CPU") { it / max * 100f } },
                )
            ),
            ChartSpec(
                title = "Consommation",
                unit = "W",
                series = listOf(
                    ChartSeries("w_in", "Total"),
                    ChartSeries("w_cpu_gpu", "CPU/GPU"),
                    ChartSeries("w_soc", "SoC"),
                ),
                footer = energySummary(log)
            ),
            ChartSpec(
                title = "Santé SLAM",
                unit = "Hz",
                series = listOf(
                    ChartSeries("cloud_hz", "Nuage"),
                    ChartSeries("odom_hz", "Odométrie"),
                ),
                bands = log.falseSpans("odom_ok") + log.falseSpans("cloud_ok")
            ),
        ).mapNotNull { spec ->
            spec.series.filter { log.has(it.key) }.takeIf { it.isNotEmpty() }
                ?.let { spec.copy(series = it) }
        }
    }

    if (specs.isEmpty()) {
        CenteredMessage("Aucune donnée exploitable dans ce journal.", onBack)
        return
    }

    val pagerState = rememberPagerState(pageCount = { specs.size })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Spacer(Modifier.width(6.dp))
            Text(
                scanName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Text(
                listOfNotNull(
                    "Durée ${formatT(log.durationS)}",
                    "${log.samples.size} pts",
                    log.static.powerMode
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalPager(
            state = pagerState,
            // Default snap threshold is half a page; on a full-screen-wide page that is a very
            // long drag, which is what made swiping feel dead. 15% commits the page change.
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapPositionalThreshold = 0.15f
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            ChartPage(spec = specs[page], log = log)
        }

        // Explicit paging controls. A swipe still works, but on a full-screen-wide page it
        // needs a long or fast drag to cross the pager's snap threshold, which made it feel
        // unresponsive — so chart switching does not depend on the gesture at all.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp, top = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                enabled = pagerState.currentPage > 0,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Courbe précédente")
            }

            specs.indices.forEach { i ->
                val active = i == pagerState.currentPage
                // Tapping a dot jumps straight to that chart.
                Box(
                    Modifier
                        .padding(horizontal = 5.dp)
                        .size(if (active) 9.dp else 7.dp)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            CircleShape
                        )
                        .clickable { scope.launch { pagerState.animateScrollToPage(i) } }
                )
            }

            IconButton(
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                enabled = pagerState.currentPage < specs.lastIndex,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Courbe suivante")
            }
        }
    }
}

/** Mean draw and total energy over the scan — the numbers that size a battery. */
private fun energySummary(log: HealthLog): String? {
    val pts = log.series("w_in")
    if (pts.size < 2) return null
    // Trapezoidal integration over the actual timestamps, so a gap in the log
    // doesn't get counted as if it were a regular interval.
    var joules = 0f
    for (i in 1 until pts.size) {
        val dt = pts[i].first - pts[i - 1].first
        if (dt <= 0f) continue
        joules += (pts[i].second + pts[i - 1].second) / 2f * dt
    }
    val span = pts.last().first - pts.first().first
    if (span <= 0f) return null
    return "Moyenne %.1f W · %.1f Wh consommés".format(joules / span, joules / 3600f)
}

/**
 * One full-screen chart.
 *
 * Every page shares the same x domain (0..log duration) and the same plot width, so a
 * feature at a given time sits at the same horizontal position on every chart — that is
 * what lets a temperature spike be read against a SLAM dropout across a swipe.
 */
@Composable
private fun ChartPage(spec: ChartSpec, log: HealthLog) {
    val series = spec.series
    val tMax = log.durationS.takeIf { it > 0f } ?: return

    // Resolve the y axis from the transformed values, padded so lines don't touch the edges.
    val axis = spec.yRange ?: run {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        series.forEach { s ->
            log.series(s.key).forEach { (_, raw) ->
                val v = s.transform(raw)
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
        if (lo > hi) return
        val pad = ((hi - lo) * 0.08f).takeIf { it > 0f } ?: 1f
        (lo - pad)..(hi + pad)
    }

    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    val bandColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
    val colors = series.indices.map { SeriesColors[it % SeriesColors.size] }
    val span = (axis.endInclusive - axis.start).takeIf { it > 0f } ?: 1f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Title + legend
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(spec.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(14.dp))
            series.forEachIndexed { i, s ->
                Box(Modifier.size(8.dp).background(colors[i], CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                spec.unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(4.dp))

        // Plot area — y labels beside a Canvas that takes the remaining height.
        // All axis text is composed with Text so the Canvas never measures text itself.
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxHeight().width(46.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                for (i in 4 downTo 0) {
                    Text(
                        "%.0f".format(axis.start + span * i / 4f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(6.dp))

            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val w = size.width
                val h = size.height

                fun px(t: Float) = t / tMax * w
                fun py(v: Float) = h - ((v - axis.start) / span * h).coerceIn(0f, h)

                // Dropout bands first, so the lines draw over them.
                spec.bands.forEach { b ->
                    val x0 = px(b.start.coerceIn(0f, tMax))
                    val x1 = px(b.endInclusive.coerceIn(0f, tMax))
                    if (x1 > x0) drawRect(bandColor, Offset(x0, 0f), Size(x1 - x0, h))
                }

                for (i in 0..4) {                       // horizontal gridlines
                    val y = h * i / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }
                for (i in 0..4) {                       // vertical time gridlines
                    val x = w * i / 4f
                    drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                }

                series.forEachIndexed { i, s ->
                    val pts = log.series(s.key)
                    if (pts.size < 2) return@forEachIndexed
                    val path = Path()
                    pts.forEachIndexed { idx, (t, raw) ->
                        val x = px(t)
                        val y = py(s.transform(raw))
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, colors[i], style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }

        // X labels, aligned with the vertical gridlines above.
        Row(modifier = Modifier.fillMaxWidth().padding(start = 52.dp)) {
            for (i in 0..4) {
                Text(
                    formatT(tMax * i / 4f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        4 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        spec.footer?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp)
            )
        }
    }
}
