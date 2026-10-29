package com.rix.vpn.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rix.vpn.core.RixMode
import com.rix.vpn.core.RixOptions
import com.rix.vpn.core.RixRuntime
import com.rix.vpn.data.ThemeMode
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    options: RixOptions,
    state: RixRuntime.State,
    diagnostics: String,
    theme: ThemeMode,
    onCycleTheme: () -> Unit,
    onModeChange: (RixMode) -> Unit,
    onToggle: () -> Unit,
    onOpenOptions: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val running = state.phase == RixRuntime.Phase.RUNNING
    val busy = state.phase == RixRuntime.Phase.STARTING || state.phase == RixRuntime.Phase.STOPPING

    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.startedAt, running) {
        while (running && state.startedAt > 0) {
            elapsed = (System.currentTimeMillis() - state.startedAt) / 1000
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RIX",
                        color = RixColors.Text,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "تونل امن WARP / MASQUE",
                        color = RixColors.TextMuted,
                        fontSize = 11.5.sp
                    )
                }
                ThemeIconButton(theme, onCycleTheme)
                Spacer(Modifier.width(10.dp))
                StatusBadge(state)
            }

            Spacer(Modifier.height(22.dp))

            // ---- primary status ----------------------------------------
            Text(
                text = statusTitle(state),
                color = RixColors.Text,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = statusSubtitle(state, options),
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
            } else {
                Spacer(Modifier.height(10.dp))
            }

            // ---- mode --------------------------------------------------
            SegmentedRow(
                options = listOf(RixMode.TUNNEL.label, RixMode.PROXY.label),
                selectedIndex = if (options.mode == RixMode.TUNNEL) 0 else 1,
                onSelect = { index ->
                    onModeChange(if (index == 0) RixMode.TUNNEL else RixMode.PROXY)
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = if (options.mode == RixMode.TUNNEL) {
                    "همهٔ ترافیک گوشی از تونل عبور می‌کند (VpnService)."
                } else {
                    "فقط یک پروکسی SOCKS5 محلی؛ هیچ ترافیکی گرفته نمی‌شود."
                },
                color = RixColors.TextMuted,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            // ---- live stats --------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatTile(
                    label = "دریافت",
                    value = formatBytes(state.rxBytes),
                    accent = RixColors.Teal,
                    icon = Icons.Rounded.ArrowDownward,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "ارسال",
                    value = formatBytes(state.txBytes),
                    accent = RixColors.Violet,
                    icon = Icons.Rounded.ArrowUpward,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatTile(
                    label = "بستهٔ دریافتی",
                    value = state.rxPackets.toString(),
                    accent = RixColors.Teal,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "بستهٔ ارسالی",
                    value = state.txPackets.toString(),
                    accent = RixColors.Violet,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(18.dp))

            // ---- navigation --------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NavButton("تنظیمات", Icons.Rounded.Settings, Modifier.weight(1f), onOpenOptions)
                NavButton("گزارش", Icons.Rounded.Description, Modifier.weight(1f), onOpenLogs)
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = diagnostics,
                color = RixColors.TextMuted.copy(alpha = 0.75f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Ltr)
            )
        }
    }
}

@Composable
private fun StatusBadge(state: RixRuntime.State) {
    val (label, color) = when (state.phase) {
        RixRuntime.Phase.RUNNING -> "فعال" to RixColors.Teal
        RixRuntime.Phase.STARTING -> "اتصال…" to RixColors.Warning
        RixRuntime.Phase.STOPPING -> "قطع…" to RixColors.Warning
        RixRuntime.Phase.ERROR -> "خطا" to RixColors.Danger
        RixRuntime.Phase.IDLE -> "خاموش" to RixColors.TextMuted
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
        Text(label, color = color, fontSize = 11.5.sp)
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

private fun statusTitle(state: RixRuntime.State): String = when (state.phase) {
    RixRuntime.Phase.RUNNING ->
        if (state.tun2socks) "تونل برقرار است" else "پروکسی آماده است"
    RixRuntime.Phase.STARTING -> "در حال اتصال…"
    RixRuntime.Phase.STOPPING -> "در حال قطع…"
    RixRuntime.Phase.ERROR -> "اتصال ناموفق"
    RixRuntime.Phase.IDLE -> "آماده به اتصال"
}

private fun statusSubtitle(state: RixRuntime.State, options: RixOptions): String = when (state.phase) {
    RixRuntime.Phase.ERROR -> state.error ?: "خطای نامشخص"
    RixRuntime.Phase.STARTING -> state.message.ifBlank { "جست‌وجوی گیت‌وی و ساخت تونل" }
    RixRuntime.Phase.RUNNING -> "${options.transport.label} • ${options.scanMode.label}"
    RixRuntime.Phase.IDLE -> "برای شروع دکمهٔ بالا را بزن"
    RixRuntime.Phase.STOPPING -> "کمی صبر کن"
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