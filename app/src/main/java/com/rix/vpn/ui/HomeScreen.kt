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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rix.vpn.core.RixMode
import com.rix.vpn.core.RixOptions
import com.rix.vpn.core.RixRuntime
import kotlinx.coroutines.delay

/**
 * The home screen, kept to what a user looks at while connecting.
 *
 * Between the orb and the two ways out of the screen there used to be four rows
 * of statistics, a mode switch, a caption, and a line of library diagnostics:
 * six blocks of equal weight, so nothing led. The diagnostics moved to the log
 * screen where they are useful, the packet counters went with them (the byte
 * tiles say the same thing at a glance), and what is left is the status, one
 * row of live numbers, and the way in.
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

    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.startedAt, running) {
        while (running && state.startedAt > 0) {
            // Same monotonic clock markRunning used: a wall-clock correction
            // would otherwise make the session timer jump or run backwards.
            elapsed = ((SystemClock.elapsedRealtime() - state.startedAt) / 1000).coerceAtLeast(0L)
            delay(1000)
        }
        if (!running) elapsed = 0
    }

    RixSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 34.dp, bottom = 26.dp),
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
                Spacer(Modifier.width(10.dp))
                StatusBadge(state)
            }

            Spacer(Modifier.height(24.dp))

            // ---- primary status ----------------------------------------
            Text(
                text = statusTitle(state, s),
                color = RixColors.Text,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = statusSubtitle(state, options, s),
                color = if (state.phase == RixRuntime.Phase.ERROR) RixColors.Danger else RixColors.TextMuted,
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(14.dp))

            PowerOrb(
                active = running,
                busy = busy,
                onClick = onToggle
            )

            Spacer(Modifier.height(6.dp))

            if (running) {
                MonoText(
                    text = formatDuration(elapsed),
                    color = RixColors.Text,
                    size = 26
                )
                Spacer(Modifier.height(4.dp))
                MonoText(
                    text = "127.0.0.1:${state.socksPort}",
                    color = RixColors.TextMuted,
                    size = 12
                )
                Spacer(Modifier.height(18.dp))
            } else if (connecting) {
                // The clock belongs to time connected, so it stays at zero here
                // and the wait gets an explicit indicator instead.
                ConnectingDots(RixColors.Warning)
                Spacer(Modifier.height(18.dp))
            } else {
                Spacer(Modifier.height(10.dp))
            }

            // ---- mode --------------------------------------------------
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
                            "همهٔ ترافیک گوشی از تونل عبور می‌کند (VpnService).",
                            "Every app's traffic goes through the tunnel (VpnService)."
                        )
                    else ->
                        s.s(
                            "فقط یک پروکسی SOCKS5 محلی؛ هیچ ترافیکی گرفته نمی‌شود.",
                            "A local SOCKS5 proxy only; no traffic is captured."
                        )
                },
                color = RixColors.TextMuted,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            // ---- live numbers ------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatTile(
                    label = s.s("دریافت", "Down"),
                    // A dash, not "0 B": the counters are missing, and drawing
                    // zero would claim the tunnel is up and moving nothing.
                    value = if (state.statsAvailable) formatBytes(state.rxBytes) else "—",
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
                StatTile(
                    label = s.s("ارسال", "Up"),
                    value = if (state.statsAvailable) formatBytes(state.txBytes) else "—",
                    accent = RixColors.Violet,
                    icon = Icons.Rounded.ArrowUpward,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(20.dp))

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
    RixRuntime.Phase.STARTING -> s.s("در حال آماده‌سازی...", "Preparing...")
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
            s.s("جست‌وجوی گیت‌وی و ساخت تونل", "Finding a gateway and building the tunnel")
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