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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rix.vpn.core.CloudflareIpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The edge scanner, on its own screen behind the drawer.
 *
 * It shows what the scanner is actually doing -- how many addresses it has
 * probed, which one it is on, and the best answers so far -- instead of a
 * decorative "scanning…" line. Every address is a real TCP + TLS handshake
 * to a Cloudflare edge; nothing here is simulated.
 */
@Composable
fun ScanScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by CloudflareIpManager.ui.collectAsState()
    val best = ui.best

    RixSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 26.dp, bottom = 30.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(RixColors.SurfaceHigh)
                        .border(1.dp, RixColors.Stroke, RoundedCornerShape(12.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.ArrowForward,
                        contentDescription = s.s("بازگشت", "Back"),
                        tint = RixColors.Text,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        s.s("اسکنر لبه", "Edge scanner"),
                        color = RixColors.Text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        s.s("آزمایش واقعی TCP و TLS", "Real TCP and TLS probes"),
                        color = RixColors.TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = when {
                            ui.scanning -> s.s(
                                "در حال آزمایش آدرس‌های کلودفلر…",
                                "Probing Cloudflare edge addresses…"
                            )
                            best != null -> s.s(
                                "بهترین آدرس: ${best.ip} · ${best.latencyMs}ms",
                                "Best address: ${best.ip} · ${best.latencyMs}ms"
                            )
                            else -> s.s(
                                "هنوز آدرسی پاسخ نداده است",
                                "No address has answered yet"
                            )
                        },
                        color = RixColors.Text,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    // A real progress bar: done/total of the bundled source.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(RixColors.Stroke)
                    ) {
                        val fraction = if (ui.total > 0) {
                            (ui.done.toFloat() / ui.total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        if (fraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(RixColors.Teal)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (ui.total > 0) "${ui.done} / ${ui.total}" else "—",
                            color = RixColors.TextMuted,
                            fontSize = 11.5.sp
                        )
                        Text(
                            text = ui.current.ifBlank { s.s("آماده", "Idle") },
                            color = RixColors.TextMuted,
                            fontSize = 11.5.sp
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(RixColors.Teal)
                            .clickable(enabled = !ui.scanning) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        CloudflareIpManager.warmUp(
                                            context.applicationContext,
                                            forceRefresh = true
                                        )
                                    }
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = RixColors.OnAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = when {
                                ui.scanning -> s.s("در حال اسکن…", "Scanning…")
                                best != null -> s.s("اسکن دوباره", "Scan again")
                                else -> s.s("شروع اسکن", "Start scan")
                            },
                            color = RixColors.OnAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (ui.ranked.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SectionTitle(s.s("آدرس‌های پاسخ‌داده", "Addresses that answered"))
                        ui.ranked.forEachIndexed { index, score ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    color = RixColors.Teal,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(22.dp)
                                )
                                MonoText(
                                    text = score.ip,
                                    color = RixColors.Text,
                                    size = 12,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${score.latencyMs} ms",
                                    color = RixColors.TextMuted,
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = s.s(
                    "هر آدرس مستقیم با TCP و TLS آزمایش می‌شود؛ هیچ ترافیکی از تونل عبور نمی‌کند و اسکن هیچ‌وقت جلوی اتصال را نمی‌گیرد. اتصال همیشه اول با مسیر خود هسته برقرار می‌شود.",
                    "Every address is probed directly with TCP and TLS; nothing goes through the tunnel and a scan never stands between you and a connection. The first connect always uses the core's own path."
                ),
                color = RixColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 18.sp
            )
        }
    }
}
