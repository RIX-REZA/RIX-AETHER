package com.rix.vpn.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rix.vpn.R
import com.rix.vpn.core.RixMode
import com.rix.vpn.core.RixOptions
import com.rix.vpn.core.RixRuntime
import kotlinx.coroutines.delay

/**
 * The home screen.
 *
 * It reads top to bottom as one answer: what is happening, how far the
 * connection has actually got, how much is moving, and only then the settings
 * that change any of it. The two blocks above the tiles are the point of this
 * version:
 *
 *  - the stage strip lights each hop on evidence, so "connected" cannot be
 *    claimed by a listener that merely accepts (the same rule the Windows
 *    client follows);
 *  - the chart is drawn from the engine's own counters, differenced over time,
 *    so an idle tunnel draws a flat line rather than a plausible-looking curve.
 *
 * The orb keeps the behaviour the user asked for and could not find: a second
 * tap while an attempt is in flight cancels it.
 */
@Composable
fun HomeScreen(
    options: RixOptions,
    state: RixRuntime.State,
    dark: Boolean,
    onToggleTheme: () -> Unit,
    onModeChange: (RixMode) -> Unit,
    onToggle: () -> Unit,
    onOpenOptions: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val s = LocalStrings.current
    val running = state.phase == RixRuntime.Phase.RUNNING
    val connecting = state.phase == RixRuntime.Phase.CONNECTING
    val busy = state.phase == RixRuntime.Phase.STARTING ||
        connecting ||
        state.phase == RixRuntime.Phase.STOPPING

    // Nothing that changes once a second is read here. The session clock, the
    // chart and the two traffic tiles each own their ticking value in their own
    // composable, so a counter tick recomposes those four and not this body --
    // which is the header, the hero panel, the stage strip, the mode switch and
    // the navigation, i.e. the whole screen, once a second, for as long as a
    // tunnel was up.

    val accent = phaseAccent(state.phase)

    RixSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 30.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ---- header -------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RixLogo(sizeDp = 44)
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RIX",
                        color = RixColors.Text,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = s.s(
                            "تونل امن WARP / MASQUE",
                            "Secure WARP / MASQUE tunnel"
                        ),
                        color = RixColors.TextMuted,
                        fontSize = 11.sp
                    )
                }
                ThemeIconButton(dark = dark, onClick = onToggleTheme)
                Spacer(Modifier.width(8.dp))
                SocialIcon(
                    imageVector = { TelegramIcon() },
                    url = "https://t.me/RIXPANEL",
                    contentDesc = "Telegram"
                )
                SocialIcon(
                    imageVector = { GitHubIcon() },
                    url = "https://github.com/RIX-REZA/RIX-AETHER",
                    contentDesc = "GitHub"
                )
                Spacer(Modifier.width(10.dp))
                StatusBadge(state)
            }

            Spacer(Modifier.height(16.dp))

            // ---- hero ---------------------------------------------------
            // One panel holds the whole answer to "what is happening": the
            // state, the reason for it, the control, and the two readings that
            // belong to the session.
            HeroPanel(accent = accent) {
                Text(
                    text = statusTitle(state, s),
                    color = RixColors.Text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = statusSubtitle(state, options, s),
                    color = if (state.phase == RixRuntime.Phase.ERROR) {
                        RixColors.Danger
                    } else {
                        RixColors.TextMuted
                    },
                    fontSize = 12.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp
                )

                Spacer(Modifier.height(10.dp))

                PowerOrb(phase = state.phase, onClick = onToggle)

                Spacer(Modifier.height(2.dp))

                when {
                    running -> {
                        // The clock is its own composable: it is one of the two
                        // things on this screen that move every second.
                        SessionClock(startedAt = state.startedAt)
                        Spacer(Modifier.height(6.dp))
                        MonoText(
                            text = "127.0.0.1:${state.socksPort}",
                            color = RixColors.TextMuted,
                            size = 12
                        )
                    }
                    // The clock belongs to time connected, so it stays at zero
                    // here and the wait gets an explicit indicator instead.
                    connecting || state.phase == RixRuntime.Phase.STARTING ->
                        ConnectingDots(RixColors.Warning)
                    else -> Unit
                }
            }

            // The error, in place and unedited. It used to live only in the log,
            // which is a screen the user has to know to open.
            if (state.phase == RixRuntime.Phase.ERROR && !state.error.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                AlertBar(text = state.error)
            }

            // The affordance, spelled out. The orb says «لغو» while an attempt
            // is in flight, and this is the sentence that tells the user the
            // second tap is the way to use it.
            if (connecting || state.phase == RixRuntime.Phase.STARTING) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = s.s(
                        "برای لغو اتصال، دوباره روی دکمه بزن",
                        "Tap the orb again to cancel"
                    ),
                    color = RixColors.Warning,
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---- how far it has got -------------------------------------
            StageStrip(stages = stages(state, options, running, s))

            Spacer(Modifier.height(16.dp))

            // ---- throughput ---------------------------------------------
            ThroughputPanel(
                running = running,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // ---- live numbers -------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiveStatTile(
                    label = s.s("دریافت", "Down"),
                    bytes = { it.rxBytes },
                    accent = RixColors.Accent,
                    icon = Icons.Rounded.ArrowDownward,
                    modifier = Modifier.weight(1f)
                )
                IpTile(
                    ip = state.egressIp,
                    colo = state.egressColo,
                    loc = state.egressLoc,
                    country = state.egressCountry,
                    city = state.egressCity,
                    isp = state.egressIsp,
                    live = running || connecting,
                    modifier = Modifier.weight(1.4f)
                )
                LiveStatTile(
                    label = s.s("ارسال", "Up"),
                    bytes = { it.txBytes },
                    accent = RixColors.AccentDeep,
                    icon = Icons.Rounded.ArrowUpward,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---- mode ---------------------------------------------------
            // A live session cannot be moved between modes -- the tunnel owns a
            // TUN and the proxy does not -- and letting the switch move used to
            // change which service the orb would stop while the other kept
            // running. Lock it while something is up and say why.
            SegmentedRow(
                options = listOf(
                    RixMode.TUNNEL.label(s),
                    RixMode.PROXY.label(s)
                ),
                selectedIndex = if (options.mode == RixMode.TUNNEL) 0 else 1,
                onSelect = { index ->
                    onModeChange(if (index == 0) RixMode.TUNNEL else RixMode.PROXY)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !running && !busy
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = when {
                    running || busy ->
                        s.s("برای تغییر حالت، اول اتصال را قطع کن.", "Disconnect first to change mode.")
                    options.mode == RixMode.TUNNEL ->
                        s.s(
                            "همهٔ ترافیک گوشی از تونل عبور میکند (VpnService).",
                            "Every app's traffic goes through the tunnel (VpnService)."
                        )
                    else ->
                        s.s(
                            "فقط یک پروکسی SOCKS5 محلی؛ هیچ ترافیکی گرفته نمیشود.",
                            "A local SOCKS5 proxy only; no traffic is captured."
                        )
                },
                color = RixColors.TextMuted,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(18.dp))

            // ---- navigation --------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NavButton(
                    label = s.s("تنظیمات", "Settings"),
                    icon = Icons.Rounded.Settings,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenOptions
                )
                NavButton(
                    label = s.s("گزارش", "Logs"),
                    icon = Icons.Rounded.Description,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenLogs
                )
            }
        }
    }
}

/**
 * The session timer.
 *
 * It is on its own so that the once-a-second tick recomposes this line and
 * nothing else. The mark it counts from is the one [RixRuntime.markRunning]
 * took, on the monotonic clock: a wall-clock correction would otherwise make
 * the timer jump or run backwards, and a VPN is exactly the app opened on a
 * phone that just came back from airplane mode.
 */
@Composable
private fun SessionClock(startedAt: Long) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        elapsed = 0L
        if (startedAt <= 0L) return@LaunchedEffect
        while (true) {
            elapsed = ((SystemClock.elapsedRealtime() - startedAt) / 1000).coerceAtLeast(0L)
            delay(1000)
        }
    }
    MonoText(text = formatDuration(elapsed), color = RixColors.Text, size = 26)
}

/**
 * The chart and its caption.
 *
 * It collects the engine's counters itself, so the readings that change every
 * second invalidate this card rather than the screen it sits on. The series is
 * built by differencing two readings against the monotonic clock, which is what
 * makes an idle tunnel draw a flat line instead of a plausible-looking curve.
 */
@Composable
private fun ThroughputPanel(running: Boolean, modifier: Modifier = Modifier) {
    val s = LocalStrings.current

    val downSeries = remember { mutableStateListOf<Float>() }
    val upSeries = remember { mutableStateListOf<Float>() }

    var lastRx by remember { mutableLongStateOf(0L) }
    var lastTx by remember { mutableLongStateOf(0L) }
    var lastAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(running) {
        downSeries.clear()
        upSeries.clear()
        lastRx = 0L
        lastTx = 0L
        lastAt = 0L
        if (!running) return@LaunchedEffect

        // The service publishes one reading a second, so reacting to those is
        // both the right pace and the only honest one: no reading, no sample,
        // and never a curve drawn from a number the engine did not give.
        RixRuntime.traffic.collect { reading ->
            val now = SystemClock.elapsedRealtime()
            if (!reading.available) {
                // Null is not zero: the engine cannot answer, so there is no
                // rate to draw and the series goes back to empty.
                if (downSeries.isNotEmpty() || upSeries.isNotEmpty()) {
                    downSeries.clear()
                    upSeries.clear()
                }
                lastAt = 0L
            } else {
                if (lastAt != 0L) {
                    val seconds = (now - lastAt) / 1000f
                    if (seconds > 0f) {
                        sample(downSeries, reading.rxBytes, lastRx, seconds)
                        sample(upSeries, reading.txBytes, lastTx, seconds)
                    }
                }
                lastRx = reading.rxBytes
                lastTx = reading.txBytes
                lastAt = now
            }
        }
    }

    // The same reading the tiles use, so the caption cannot claim a rate while
    // they are drawing a dash for the very same counters.
    val traffic by RixRuntime.traffic.collectAsState()
    val rate = (downSeries.lastOrNull() ?: 0f) + (upSeries.lastOrNull() ?: 0f)

    GlassCard(modifier = modifier) {
        Column {
            ChartLegend(
                downLabel = s.s("دریافت", "Down"),
                upLabel = s.s("ارسال", "Up"),
                rateLabel = if (traffic.available) "${formatBytes(rate.toLong())}/s" else "—"
            )
            Spacer(Modifier.height(10.dp))
            ThroughputChart(
                down = downSeries,
                up = upSeries,
                // A tunnel that is not running has nothing to draw, and
                // a flat line at zero would read as a real measurement.
                modifier = Modifier.fillMaxWidth(),
                height = 84.dp
            )
        }
    }
}

/**
 * A traffic tile that reads the counters on their own flow.
 *
 * The dash is not decoration: when the engine cannot answer, drawing "0 B"
 * would claim the tunnel is up and moving nothing, which is the more
 * convincing of the two wrong answers.
 */
@Composable
private fun LiveStatTile(
    label: String,
    bytes: (RixRuntime.Traffic) -> Long,
    accent: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val traffic by RixRuntime.traffic.collectAsState()
    StatTile(
        label = label,
        value = if (traffic.available) formatBytes(bytes(traffic)) else "—",
        accent = accent,
        icon = icon,
        modifier = modifier
    )
}

/**
 * One reading of a counter, turned into a rate.
 *
 * A counter that has gone backwards does not belong to this session -- the
 * service clears the counters when an attempt starts -- so the sample is
 * dropped and the series begins again rather than drawing a spike that never
 * happened.
 */
private fun sample(series: MutableList<Float>, total: Long, previous: Long, seconds: Float) {
    if (total < previous) {
        series.clear()
        return
    }
    series.add(((total - previous) / seconds).coerceAtLeast(0f))
    while (series.size > 60) series.removeAt(0)
}

/**
 * The four (or five) stages of a connection, and the evidence for each.
 *
 * `running` is only reached after a real request came back through the tunnel,
 * so it is the proof for the tunnel stage and for nothing else: the exit stage
 * waits for an address to actually answer.
 */
private fun stages(
    state: RixRuntime.State,
    options: RixOptions,
    running: Boolean,
    s: Strings
): List<Pair<String, StageState>> {
    val started = state.phase != RixRuntime.Phase.IDLE

    return buildList {
        add(
            s.s("هسته", "Core") to when {
                running -> StageState.DONE
                started -> StageState.LIT
                else -> StageState.OFF
            }
        )
        add(
            s.s("شنونده", "Listener") to when {
                state.socksPort <= 0 -> StageState.OFF
                running -> StageState.DONE
                else -> StageState.LIT
            }
        )
        // Left out entirely when Psiphon is off: a greyed stage reads as one
        // that is waiting, and this one is not in the path at all.
        if (options.psiphon) {
            add(
                "Psiphon" to when {
                    running -> StageState.DONE
                    state.phase == RixRuntime.Phase.CONNECTING ||
                        state.phase == RixRuntime.Phase.STARTING -> StageState.LIT
                    else -> StageState.OFF
                }
            )
        }
        add(
            s.s("تونل", "Tunnel") to if (running) StageState.DONE else StageState.OFF
        )
        add(
            s.s("خروج", "Exit") to when {
                !running -> StageState.OFF
                state.egressIp.isNotBlank() -> StageState.DONE
                else -> StageState.LIT
            }
        )
    }
}

/**
 * The colour a phase paints itself in.
 *
 * One table, so the badge, the hero panel's tint and the orb's accent cannot
 * drift apart and leave a red panel around a green orb.
 */
@Composable
private fun phaseAccent(phase: RixRuntime.Phase): Color = when (phase) {
    RixRuntime.Phase.RUNNING -> RixColors.Accent
    RixRuntime.Phase.ERROR -> RixColors.Danger
    RixRuntime.Phase.STARTING,
    RixRuntime.Phase.CONNECTING,
    RixRuntime.Phase.STOPPING -> RixColors.Warning
    RixRuntime.Phase.IDLE -> RixColors.Accent
}

/**
 * The panel the whole screen is about.
 *
 * A tinted, rounded well rather than another flat card: it gives the status,
 * the orb and the session readings one shared surface, so the eye lands there
 * first. The tint follows the phase, which means the error state is legible
 * from across the room without reading a word.
 */
@Composable
private fun HeroPanel(
    accent: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = if (RixColors.isDark) 0.15f else 0.08f),
                        RixColors.Surface.copy(alpha = 0.92f)
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(30.dp))
            .padding(horizontal = 14.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
private fun StatusBadge(state: RixRuntime.State) {
    val s = LocalStrings.current
    val (label, color) = when (state.phase) {
        RixRuntime.Phase.RUNNING -> s.s("متصل", "Connected") to RixColors.Accent
        RixRuntime.Phase.CONNECTING -> s.s("در حال اتصال", "Connecting") to RixColors.Warning
        RixRuntime.Phase.STARTING -> s.s("آمادهسازی", "Preparing") to RixColors.Warning
        RixRuntime.Phase.STOPPING -> s.s("قطع...", "Stopping") to RixColors.Warning
        RixRuntime.Phase.ERROR -> s.s("خطا", "Error") to RixColors.Danger
        RixRuntime.Phase.IDLE -> s.s("خاموش", "Off") to RixColors.TextMuted
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .height(7.dp)
                .width(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(label, color = color, fontSize = 11.5.sp, maxLines = 1)
    }
}

@Composable
private fun NavButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(RixColors.SurfaceHigh, RixColors.Surface)
                )
            )
            .border(1.dp, RixColors.Stroke, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = RixColors.TextMuted)
        Spacer(Modifier.width(8.dp))
        Text(label, color = RixColors.Text, fontSize = 13.5.sp)
    }
}

private fun statusTitle(state: RixRuntime.State, s: Strings): String = when (state.phase) {
    RixRuntime.Phase.RUNNING ->
        if (state.tun2socks) s.s("تونل برقرار است", "Tunnel is up")
        else s.s("پروکسی آماده است", "Proxy is ready")
    RixRuntime.Phase.CONNECTING -> s.s("در حال اتصال...", "Connecting...")
    RixRuntime.Phase.STARTING -> s.s("در حال آمادهسازی...", "Preparing...")
    RixRuntime.Phase.STOPPING -> s.s("در حال قطع...", "Disconnecting...")
    RixRuntime.Phase.ERROR -> s.s("اتصال ناموفق", "Connection failed")
    RixRuntime.Phase.IDLE -> s.s("آماده به اتصال", "Ready to connect")
}

private fun statusSubtitle(
    state: RixRuntime.State,
    options: RixOptions,
    s: Strings
): String = when (state.phase) {
    RixRuntime.Phase.ERROR -> state.error ?: s.s("خطای نامشخص", "Unknown error")
    // The wait is stated in terms of what is actually being waited for:
    // a real request has to come back through the tunnel before this screen
    // is allowed to say متصل.
    RixRuntime.Phase.CONNECTING ->
        state.message.ifBlank {
            s.s("آزمودن تونل با ترافیک واقعی...", "Testing the tunnel with real traffic...")
        }
    RixRuntime.Phase.STARTING ->
        state.message.ifBlank {
            s.s("جستوجوی گیتوی و ساخت تونل", "Finding a gateway and building the tunnel")
        }
    RixRuntime.Phase.RUNNING -> buildString {
        // The label is the transport that actually carried the tunnel, which
        // may differ from the picked one when the fallback chain settled on
        // something the network allows.
        append(state.activeTransport.ifBlank { options.transport.label(s) })
        // Psiphon is the last hop, and the one the exit address belongs to, so
        // a session that uses it says so rather than looking like plain WARP.
        if (options.psiphon) append(" → Psiphon")
        append(" • ")
        append(options.scanMode.label(s))
        if (state.egressColo.isNotBlank()) {
            append(" • ")
            append(s.s("لبهٔ ", "edge "))
            append(state.egressColo)
        }
    }
    RixRuntime.Phase.IDLE -> s.s("برای شروع دکمهٔ بالا را بزن", "Tap the orb above to connect")
    RixRuntime.Phase.STOPPING -> s.s("کمی صبر کن", "One moment")
}

/**
 * Three dots that breathe while the tunnel is being proven.
 *
 * Same rule as the orb: the animated value is held as [State] and read inside
 * the draw scope, so a frame costs a redraw rather than a recomposition of the
 * screen this sits on.
 */
@Composable
private fun ConnectingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "connecting")
    val phase: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots"
    )
    Canvas(
        modifier = Modifier
            .width(52.dp)
            .height(14.dp)
    ) {
        val p = phase.value
        val radius = size.height * 0.20f
        repeat(3) { index ->
            // Triangle wave: ramp up then down, so the dots read as a wave
            // travelling along the row instead of three independent blinks.
            val offset = (p + index / 3f) % 1f
            val wave = if (offset < 0.5f) offset * 2f else (1f - offset) * 2f
            drawCircle(
                color = color.copy(alpha = 0.30f + 0.65f * wave),
                radius = radius * (1f + 0.25f * wave),
                center = Offset(size.width * (0.18f + 0.32f * index), size.height / 2f)
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.size - 1) {
        value /= 1024.0
        index++
    }
    return String.format(java.util.Locale.US, "%.2f %s", value, units[index])
}

@Composable
internal fun SocialIcon(
    imageVector: @Composable () -> ImageVector,
    url: String,
    contentDesc: String
) {
    val context = LocalContext.current
    IconButton(onClick = { openUrl(context, url) }) {
        Icon(
            imageVector = imageVector(),
            contentDescription = contentDesc,
            tint = RixColors.TextMuted,
            modifier = Modifier.size(24.dp)
        )
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } catch (e: Exception) {
        // ignore
    }
}

/** Custom Telegram icon for the header social links. */
@Composable
private fun TelegramIcon(): ImageVector {
    return ImageVector.vectorResource(id = R.drawable.ic_telegram)
}

/** Custom GitHub icon for the header social links. */
@Composable
private fun GitHubIcon(): ImageVector {
    return ImageVector.vectorResource(id = R.drawable.ic_github)
}
