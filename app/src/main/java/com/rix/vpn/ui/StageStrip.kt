package com.rix.vpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * How far a connection has actually got, in the order it gets there.
 *
 * Every stage lights on evidence and nothing else:
 *
 *   core     a job exists -- the phase says so, and no more than that
 *   listener the SOCKS5 port is known, which only happens once it accepted
 *   tunnel   a real request came back through it, which is the only proof
 *   exit     an address answered, so we know where traffic appears to come from
 *
 * A listener that accepts is not a tunnel, so it is never drawn as one. This is
 * the same rule the Windows client follows, and the reason the two agree about
 * what "connected" means.
 *
 * A stage that is not part of this connection -- Psiphon, when it is off -- is
 * left out entirely rather than greyed: a dimmed stage reads as "waiting".
 */
enum class StageState { OFF, LIT, DONE }

@Composable
fun StageStrip(
    stages: List<Pair<String, StageState>>,
    modifier: Modifier = Modifier
) {
    // The flow runs the way the screen reads, so the arrow flips with the
    // direction rather than always pointing left-to-right.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val arrow = if (rtl) "←" else "→"

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        stages.forEachIndexed { index, (name, state) ->
            if (index > 0) {
                Text(
                    text = arrow,
                    color = RixColors.TextMuted.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            Stage(name, state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stage(name: String, state: StageState, modifier: Modifier) {
    val lit = when (state) {
        StageState.OFF -> RixColors.TextMuted
        StageState.LIT -> RixColors.Warning
        StageState.DONE -> RixColors.Teal
    }
    Column(
        modifier = modifier.alpha(if (state == StageState.OFF) 0.55f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(lit.copy(alpha = if (state == StageState.OFF) 0.40f else 1f))
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = name,
            color = if (state == StageState.OFF) RixColors.TextMuted else RixColors.Text,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The last minute of throughput, drawn from the engine's own counters.
 *
 * The caller hands in rates it computed from two readings of those counters and
 * the time between them, so nothing here is invented: an empty series draws an
 * empty chart rather than a plausible-looking curve. The peak is per-window,
 * which is what makes a quiet connection readable at all.
 *
 * The newest sample sits on the right, as charts do.
 */
@Composable
fun ThroughputChart(
    down: List<Float>,
    up: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 92.dp,
    gridColor: Color = RixColors.Stroke
) {
    // Read outside the draw scope: the palette accessors are composable, and a
    // Canvas lambda is not.
    val downColor = RixColors.Teal
    val upColor = RixColors.Violet

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val w = size.width
        val h = size.height

        repeat(3) { index ->
            val y = h * (index + 1) / 4f
            drawLine(
                color = gridColor.copy(alpha = 0.35f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f
            )
        }

        // A floor on the scale so a single stray byte does not fill the panel
        // and make an idle tunnel look busy.
        val peak = maxOf(
            1024f,
            (down.maxOrNull() ?: 0f),
            (up.maxOrNull() ?: 0f)
        )
        val step = w / (CAPACITY - 1)

        drawSeries(down, downColor, peak, step)
        drawSeries(up, upColor, peak, step)
    }
}

private const val CAPACITY = 60

private fun DrawScope.drawSeries(data: List<Float>, color: Color, peak: Float, step: Float) {
    if (data.size < 2) return

    val height = size.height
    val first = CAPACITY - data.size
    fun x(index: Int) = (first + index) * step
    fun y(value: Float) = height - (value / peak).coerceIn(0f, 1f) * (height - 8f) - 4f

    val area = Path().apply {
        moveTo(x(0), height)
        data.forEachIndexed { index, value -> lineTo(x(index), y(value)) }
        lineTo(x(data.lastIndex), height)
        close()
    }
    drawPath(area, color.copy(alpha = 0.13f))

    val line = Path().apply {
        data.forEachIndexed { index, value ->
            if (index == 0) moveTo(x(index), y(value)) else lineTo(x(index), y(value))
        }
    }
    drawPath(line, color, style = Stroke(width = 2f))
}

/**
 * A failure, said in place.
 *
 * The status line already changes colour for an error, but the reason used to
 * live only in the log -- which is a screen the user has to know to open. The
 * message comes from the core or the service, unedited.
 */
@Composable
fun AlertBar(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RixColors.Danger.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "!",
            color = RixColors.Danger,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.size(9.dp))
        Text(
            text = text,
            color = RixColors.Text,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

/** The legend and the readings under the chart, kept in one row. */
@Composable
fun ChartLegend(
    downLabel: String,
    upLabel: String,
    rateLabel: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LegendDot(RixColors.Teal)
        Text(downLabel, color = RixColors.TextMuted, fontSize = 11.sp)
        LegendDot(RixColors.Violet)
        Text(upLabel, color = RixColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        MonoText(text = rateLabel, color = RixColors.Text, size = 11)
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color)
    )
}
