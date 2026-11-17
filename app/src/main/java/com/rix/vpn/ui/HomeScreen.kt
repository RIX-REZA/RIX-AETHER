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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rix.vpn.core.RixMode
import com.rix.vpn.core.RixOptions
import com.rix.vpn.core.RixRuntime
import kotlinx.coroutines.delay

/**
 * The home screen, deliberately small.
 *
 * It answers one question -- what is happening right now -- with the header,
 * the hero panel, the orb and the two counters. Everything else (the scanner,
 * the settings and the log) lives behind the hamburger drawer, so the first
 * screen is not a dashboard with several competing controls.
 *
 * The orb keeps the behaviour the user asked for and could not find: a second
 * tap while an attempt is in flight cancels it.
 */
@Composable
fun HomeScreen(
    options: RixOptions,
    state: RixRuntime.State,
    showScanTip: Boolean,
    onModeChange: (RixMode) -> Unit,
    onToggle: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenScan: () -> Unit,
    onDismissScanTip: () -> Unit
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
                MenuButton(onClick = onOpenMenu)
                Spacer(Modifier.width(10.dp))
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

            // ---- one-time scanner tip -----------------------------------
            // Not a demand and not a gate: it says where a better address can
            // be found and closes for good. A scan is never needed to connect.
            if (showScanTip && !options.psiphon) {
                Spacer(Modifier.height(14.dp))
                ScanTipCard(onOpenScan = onOpenScan, onDismiss = onDismissScanTip)
            }

            Spacer(Modifier.height(16.dp))

            // ---- live numbers -------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiveStatTile(
                    label = s.s("دریافت", "Down"),
                    bytes = { it.rxBytes },
                    accent = RixColors.Teal,
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
                    accent = RixColors.Teal,
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

        }
    }
}

/** The hamburger. Everything that is not the connection lives behind it. */
@Composable
private fun MenuButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RixColors.SurfaceHigh)
            .border(1.dp, RixColors.Stroke, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Menu,
            contentDescription = LocalStrings.current.s("منو", "Menu"),
            tint = RixColors.Text,
            modifier = Modifier.size(21.dp)
        )
    }
}

/**
 * A one-time pointer to the scanner. The × is the whole point: the tip is
 * information, not a step, so dismissing it never blocks a connection.
 */
@Composable
private fun ScanTipCard(onOpenScan: () -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RixColors.Teal.copy(alpha = 0.10f))
            .border(1.dp, RixColors.Teal.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenScan)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = s.s(
                    "برای اتصال بهتر می‌توانی از منوی همبرگری یک اسکن تازه بزنی و آی‌پی جدید بگیری.",
                    "For a better connection, run a fresh scan from the hamburger menu and get a new IP."
                ),
                color = RixColors.Text,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = s.s(
                    "اتصال بدون اسکن هم همیشه کار می‌کند.",
                    "Connecting always works without a scan."
                ),
                color = RixColors.TextMuted,
                fontSize = 10.5.sp
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = s.s("بستن", "Dismiss"),
                tint = RixColors.TextMuted,
                modifier = Modifier.size(16.dp)
            )
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
/**
 * The colour a phase paints itself in.
 *
 * One table, so the badge, the hero panel's tint and the orb's accent cannot
 * drift apart and leave a red panel around a green orb.
 */
@Composable
private fun phaseAccent(phase: RixRuntime.Phase): Color = when (phase) {
    RixRuntime.Phase.RUNNING -> RixColors.Teal
    RixRuntime.Phase.ERROR -> RixColors.Danger
    RixRuntime.Phase.STARTING,
    RixRuntime.Phase.CONNECTING,
    RixRuntime.Phase.STOPPING -> RixColors.Warning
    RixRuntime.Phase.IDLE -> RixColors.Teal
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
        RixRuntime.Phase.RUNNING -> s.s("متصل", "Connected") to RixColors.Teal
        RixRuntime.Phase.CONNECTING -> s.s("در حال اتصال", "Connecting") to RixColors.Warning
        RixRuntime.Phase.STARTING -> s.s("آماده‌سازی", "Preparing") to RixColors.Warning
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

