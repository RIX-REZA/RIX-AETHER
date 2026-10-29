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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rix.vpn.core.IpFamily
import com.rix.vpn.core.LogLevel
import com.rix.vpn.core.PerfProfile
import com.rix.vpn.core.RixOptions
import com.rix.vpn.core.ScanMode
import com.rix.vpn.core.SplitMode
import com.rix.vpn.core.Transport
import kotlinx.coroutines.delay

/**
 * Settings.
 *
 * The previous version stacked nine cards of equal weight, so the screen opened
 * as a wall of controls with no hierarchy and the two or three settings that
 * actually get touched were lost in it. This version:
 *
 *  - opens with a summary of the current configuration, so the screen states
 *    what is configured before offering ways to change it;
 *  - offers four presets that set a coherent group of knobs at once;
 *  - folds the rarely-touched groups behind [ExpandableCard], leaving the
 *    transport group open;
 *  - can restore the defaults without hunting for each control.
 */
@Composable
fun OptionsScreen(
    options: RixOptions,
    onOptionsChange: (RixOptions) -> Unit,
    onBack: () -> Unit,
    onPickApps: () -> Unit
) {
    var draft by remember(options) { mutableStateOf(options) }

    // Debounced autosave: chips and switches apply at once, typing does not
    // hammer DataStore on every keystroke.
    LaunchedEffect(draft) {
        if (draft != options) {
            delay(350)
            onOptionsChange(draft)
        }
    }

    RixSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 28.dp, bottom = 30.dp)
        ) {
            // ---------------------------------------------------- header
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Rounded.ArrowForward, "بازگشت", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "تنظیمات",
                        color = RixColors.Text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "پیکربندی هستهٔ تونل",
                        color = RixColors.TextMuted,
                        fontSize = 11.sp
                    )
                }
                RoundIconButton(
                    Icons.Rounded.RestartAlt,
                    "بازگشت به پیشفرض",
                    tint = RixColors.TextMuted
                ) {
                    // The mode and the split list are choices about *this*
                    // connection, not core tuning; a reset must not silently
                    // drop the apps the user picked.
                    draft = RixOptions(
                        mode = draft.mode,
                        splitMode = draft.splitMode,
                        splitApps = draft.splitApps
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // --------------------------------------------------- summary
            SummaryCard(draft)

            Spacer(Modifier.height(14.dp))

            // --------------------------------------------------- presets
            SectionTitle("پیکربندی آماده")
            val presets = presets()
            presets.forEach { preset ->
                PresetRow(
                    preset = preset,
                    active = preset.matches(draft),
                    onClick = { draft = preset.configure(draft) }
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(6.dp))

            // ----------------------------------------------- transport
            ExpandableCard(
                title = "مسیر اتصال",
                subtitle = "${draft.transport.label} • ${draft.scanMode.label}",
                icon = Icons.Rounded.Route,
                initiallyExpanded = true,
                accent = RixColors.Violet
            ) {
                SectionTitle("پروتکل (Transport)")
                ChipFlow {
                    Transport.entries.forEach { transport ->
                        RixChip(
                            label = transport.label,
                            selected = draft.transport == transport,
                            onClick = { draft = draft.copy(transport = transport) }
                        )
                    }
                }
                Text(
                    text = draft.transport.desc,
                    color = RixColors.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )

                Spacer(Modifier.height(16.dp))
                SectionTitle("جستوجوی گیتوی (Scan)")
                ChipFlow {
                    ScanMode.entries.forEach { mode ->
                        RixChip(
                            label = mode.label,
                            selected = draft.scanMode == mode,
                            onClick = { draft = draft.copy(scanMode = mode) }
                        )
                    }
                }

                SettingSwitch(
                    title = "Ironclad",
                    subtitle = "هر کاندید فقط پس از عبور ترافیک واقعی پذیرفته میشود",
                    checked = draft.ironclad,
                    onCheckedChange = { draft = draft.copy(ironclad = it) }
                )

                SettingSwitch(
                    title = "چرخش خودکار پروتکل",
                    subtitle = "اگر پروتکل انتخابی تونل را برقرار نکرد، MASQUE/HTTP-2 و بعد Gool و MASQUE در MASQUE امتحان می‌شوند",
                    checked = draft.autoFallback,
                    onCheckedChange = { draft = draft.copy(autoFallback = it) }
                )
            }

            Spacer(Modifier.height(12.dp))

            // --------------------------------------------------- network
            ExpandableCard(
                title = "شبکه",
                subtitle = "پورت ${draft.bindPort} • MTU ${draft.mtu} • ${draft.ipFamily.label}",
                icon = Icons.Rounded.Wifi,
                accent = RixColors.Teal
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        label = "پورت SOCKS5",
                        value = draft.bindPort.toString(),
                        modifier = Modifier.weight(1f)
                    ) { text ->
                        text.toIntOrNull()?.let {
                            if (it in 1024..65535) draft = draft.copy(bindPort = it)
                        }
                    }
                    NumberField(
                        label = "MTU",
                        value = draft.mtu.toString(),
                        modifier = Modifier.weight(1f)
                    ) { text ->
                        text.toIntOrNull()?.let {
                            if (it in 1280..9000) draft = draft.copy(mtu = it)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                TextField(
                    label = "DNS (با کاما جدا کن)",
                    value = draft.dns,
                    hint = "1.1.1.1,1.0.0.1"
                ) { draft = draft.copy(dns = it) }

                Spacer(Modifier.height(14.dp))
                SectionTitle("خانوادهٔ آدرس")
                ChipFlow {
                    IpFamily.entries.forEach { family ->
                        RixChip(
                            label = family.label,
                            selected = draft.ipFamily == family,
                            onClick = { draft = draft.copy(ipFamily = family) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ----------------------------------------------- split tunnel
            ExpandableCard(
                title = "اسپلیت تانل",
                subtitle = if (draft.splitMode == SplitMode.ALL) {
                    "همهٔ برنامهها"
                } else {
                    "${draft.splitMode.label} • ${draft.splitApps.size} برنامه"
                },
                icon = Icons.Rounded.CallSplit,
                accent = RixColors.Violet
            ) {
                Text(
                    "فقط در حالت «تونل کامل» اثر دارد؛ در حالت پروکسی هیچ بستهای گرفته نمیشود.",
                    color = RixColors.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(12.dp))

                ChipFlow {
                    SplitMode.entries.forEach { mode ->
                        RixChip(
                            label = mode.label,
                            selected = draft.splitMode == mode,
                            onClick = { draft = draft.copy(splitMode = mode) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (draft.splitMode == SplitMode.ALL) {
                    SettingSwitch(
                        title = "خود اپ از تونل مستثنی شود",
                        subtitle = "لازم است؛ در غیر این صورت سوکتهای هسته داخل تونل گیر میکنند",
                        checked = draft.excludeSelf,
                        onCheckedChange = { draft = draft.copy(excludeSelf = it) }
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(RixColors.Surface)
                            .border(1.dp, RixColors.Stroke, RoundedCornerShape(14.dp))
                            .clickable(onClick = onPickApps)
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Apps,
                            contentDescription = null,
                            tint = RixColors.Violet,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "انتخاب برنامهها",
                            color = RixColors.Text,
                            fontSize = 13.5.sp,
                            modifier = Modifier.weight(1f)
                        )
                        MonoText("${draft.splitApps.size} انتخابشده", RixColors.TextMuted, 12)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------- behaviour
            ExpandableCard(
                title = "رفتار اتصال",
                subtitle = "اتصال مجدد ${draft.reconnectSecs} ثانیه • Keepalive ${draft.keepalive}s",
                icon = Icons.Rounded.Timer,
                accent = RixColors.Teal
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        label = "تأخیر اتصال مجدد (ث)",
                        value = draft.reconnectSecs.toString(),
                        modifier = Modifier.weight(1f)
                    ) { text ->
                        text.toIntOrNull()?.let { draft = draft.copy(reconnectSecs = it.coerceIn(0, 600)) }
                    }
                    NumberField(
                        label = "Keepalive (ث)",
                        value = draft.keepalive.toString(),
                        modifier = Modifier.weight(1f)
                    ) { text ->
                        text.toIntOrNull()?.let { draft = draft.copy(keepalive = it.coerceIn(0, 3600)) }
                    }
                }

                SettingSwitch(
                    title = "اتصال مجدد سریع",
                    subtitle = "استفاده از آخرین گیتوی کارآمد بدون اسکن تازه",
                    checked = draft.quickReconnect,
                    onCheckedChange = { draft = draft.copy(quickReconnect = it) }
                )

                SettingSwitch(
                    title = "Fragment کردن ClientHello",
                    subtitle = "عبور از DPI روی مسیر HTTP/2 و فراخوانیهای WARP",
                    checked = draft.fragment,
                    onCheckedChange = { draft = draft.copy(fragment = it) }
                )

                if (draft.fragment) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextField(
                            label = "fragment-size",
                            value = draft.fragmentSize,
                            hint = "مثلاً 40 یا 20-60",
                            modifier = Modifier.weight(1f)
                        ) { draft = draft.copy(fragmentSize = it) }
                        TextField(
                            label = "fragment-delay",
                            value = draft.fragmentDelay,
                            hint = "میلیثانیه",
                            modifier = Modifier.weight(1f)
                        ) { draft = draft.copy(fragmentDelay = it) }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------ resources
            ExpandableCard(
                title = "منابع و گزارش",
                subtitle = "${draft.perf.label} • لاگ ${draft.logLevel.label}",
                icon = Icons.Rounded.Speed,
                accent = RixColors.Violet
            ) {
                SectionTitle("پروفایل منابع")
                ChipFlow {
                    PerfProfile.entries.forEach { profile ->
                        RixChip(
                            label = profile.label,
                            selected = draft.perf == profile,
                            onClick = { draft = draft.copy(perf = profile) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionTitle("سطح لاگ")
                ChipFlow {
                    LogLevel.entries.forEach { level ->
                        RixChip(
                            label = level.label,
                            selected = draft.logLevel == level,
                            onClick = { draft = draft.copy(logLevel = level) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // -------------------------------------------------- advanced
            ExpandableCard(
                title = "پیشرفته",
                subtitle = "گیتوی دستی، پروکسی بالادستی، ECH، مسیرها",
                icon = Icons.Rounded.Tune,
                accent = RixColors.Warning
            ) {
                TextField(
                    label = "کشور خروجی (exit-loc)",
                    value = draft.exitLoc,
                    hint = "مثلاً DE یا IR"
                ) { draft = draft.copy(exitLoc = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = "peer دستی (ip:port)",
                    value = draft.peer,
                    hint = "188.114.96.1:2408"
                ) { draft = draft.copy(peer = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = "MASQUE SNI",
                    value = draft.masqueSni,
                    hint = "خالی = پیشفرض هسته"
                ) { draft = draft.copy(masqueSni = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = "پروکسی بالادستی (http-proxy)",
                    value = draft.upstreamProxy,
                    hint = "http://host:8080 یا socks5://127.0.0.1:1080"
                ) { draft = draft.copy(upstreamProxy = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = "ECH (auto یا base64)",
                    value = draft.ech,
                    hint = "خالی = خاموش"
                ) { draft = draft.copy(ech = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = "route-direct",
                    value = draft.routeDirect,
                    hint = "دامنه/آدرسهایی که مستقیم بروند"
                ) { draft = draft.copy(routeDirect = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = "route-block",
                    value = draft.routeBlock,
                    hint = "دامنه/آدرسهایی که مسدود شوند"
                ) { draft = draft.copy(routeBlock = it) }
            }

            Spacer(Modifier.height(12.dp))

            // ----------------------------------------------------- extra
            ExpandableCard(
                title = "آرگومانهای دلخواه هسته",
                subtitle = if (draft.extraArgs.isBlank()) {
                    "خالی"
                } else {
                    "${draft.extraArgs.trim().split(Regex("\\s+")).size} فلگ"
                },
                icon = Icons.Rounded.Terminal,
                accent = RixColors.TextMuted
            ) {
                Text(
                    "هر فلگ دیگری از هسته را اینجا بنویس؛ آخر اعمال میشود.",
                    color = RixColors.TextMuted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.extraArgs,
                    onValueChange = { draft = draft.copy(extraArgs = it) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    placeholder = {
                        Text(
                            "--no-grease --startup-secs 40",
                            color = RixColors.TextMuted.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Ltr)
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = RixColors.Text,
                        fontSize = 12.5.sp,
                        textDirection = TextDirection.Ltr
                    ),
                    colors = fieldColors()
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "تغییرات بهصورت خودکار ذخیره میشوند",
                color = RixColors.TextMuted.copy(alpha = 0.7f),
                fontSize = 10.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
        }
    }
}

// ------------------------------------------------------------------ presets

/**
 * A named bundle of settings.
 *
 * [matches] is what lets the row show as active without keeping a separate
 * "selected preset" field that would go stale the moment a chip is touched.
 */
private class Preset(
    val label: String,
    val hint: String,
    val icon: ImageVector,
    val accent: Color,
    val configure: (RixOptions) -> RixOptions,
    val matches: (RixOptions) -> Boolean
)

@Composable
private fun presets(): List<Preset> = listOf(
    Preset(
        label = "پیشفرض",
        hint = "MASQUE/HTTP-3 • اسکن پیشفرض",
        icon = Icons.Rounded.RestartAlt,
        accent = RixColors.TextMuted,
        configure = { o ->
            RixOptions(mode = o.mode, splitMode = o.splitMode, splitApps = o.splitApps)
        },
        matches = { o ->
            o == RixOptions(mode = o.mode, splitMode = o.splitMode, splitApps = o.splitApps)
        }
    ),
    Preset(
        label = "ایران (مقاوم)",
        hint = "MASQUE/HTTP-2 روی TCP 443، با Fragment و اسکن کامل",
        icon = Icons.Rounded.Security,
        accent = RixColors.Warning,
        configure = { o ->
            o.copy(
                transport = Transport.MASQUE_H2,
                scanMode = ScanMode.THOROUGH,
                fragment = true,
                fragmentSize = "40",
                fragmentDelay = "10",
                autoFallback = true,
                perf = PerfProfile.MEDIUM,
                logLevel = LogLevel.INFO
            )
        },
        matches = { o ->
            o.transport == Transport.MASQUE_H2 && o.fragment && o.scanMode == ScanMode.THOROUGH
        }
    ),
    Preset(
        label = "سریعترین",
        hint = "MASQUE/HTTP-3 با اسکن Turbo و پروفایل پرقدرت",
        icon = Icons.Rounded.RocketLaunch,
        accent = RixColors.Teal,
        configure = { o ->
            o.copy(
                transport = Transport.MASQUE_H3,
                scanMode = ScanMode.TURBO,
                perf = PerfProfile.HIGH,
                logLevel = LogLevel.WARN,
                fragment = false,
                ironclad = false,
                reconnectSecs = 1,
                keepalive = 5
            )
        },
        matches = { o ->
            o.transport == Transport.MASQUE_H3 &&
                o.scanMode == ScanMode.TURBO &&
                o.perf == PerfProfile.HIGH
        }
    ),
    Preset(
        label = "ضد DPI",
        hint = "MASQUE در MASQUE، اسکن کامل و Fragment",
        icon = Icons.Rounded.VerifiedUser,
        accent = RixColors.Violet,
        configure = { o ->
            o.copy(
                transport = Transport.MIM,
                scanMode = ScanMode.THOROUGH,
                fragment = true,
                fragmentSize = "40",
                fragmentDelay = "10",
                perf = PerfProfile.MEDIUM,
                logLevel = LogLevel.WARN
            )
        },
        matches = { o -> o.transport == Transport.MIM && o.fragment }
    ),
    Preset(
        label = "کممصرف",
        hint = "WireGuard، پروفایل کممصرف و Keepalive بلند",
        icon = Icons.Rounded.Bolt,
        accent = RixColors.Warning,
        configure = { o ->
            o.copy(
                transport = Transport.WIREGUARD,
                scanMode = ScanMode.BALANCED,
                perf = PerfProfile.LOW,
                keepalive = 25,
                logLevel = LogLevel.ERROR,
                fragment = false
            )
        },
        matches = { o ->
            o.transport == Transport.WIREGUARD && o.perf == PerfProfile.LOW
        }
    )
)

@Composable
private fun PresetRow(preset: Preset, active: Boolean, onClick: () -> Unit) {
    val accent = preset.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) accent.copy(alpha = if (RixColors.isDark) 0.14f else 0.09f)
                else RixColors.Surface.copy(alpha = 0.7f)
            )
            .border(
                1.dp,
                if (active) accent.copy(alpha = 0.7f) else RixColors.Stroke,
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(accent.copy(alpha = if (RixColors.isDark) 0.16f else 0.11f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(preset.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                preset.label,
                color = RixColors.Text,
                fontSize = 13.5.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(preset.hint, color = RixColors.TextMuted, fontSize = 10.5.sp)
        }
        if (active) {
            Spacer(Modifier.width(8.dp))
            RixBadge("فعال", accent)
        }
    }
}

// ------------------------------------------------------------------ summary

/** What is configured right now, before any of the controls below. */
@Composable
private fun SummaryCard(opts: RixOptions) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        RixColors.SurfaceHigh.copy(alpha = 0.92f),
                        RixColors.Surface.copy(alpha = 0.86f)
                    )
                )
            )
            .border(1.dp, RixColors.Stroke.copy(alpha = 0.75f), RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    opts.transport.label,
                    color = RixColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                opts.transport.badge?.let { RixBadge(it, RixColors.Violet) }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                opts.transport.desc,
                color = RixColors.TextMuted,
                fontSize = 10.5.sp,
                lineHeight = 15.sp
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("پورت", opts.bindPort.toString(), Modifier.weight(1f))
                MiniStat("MTU", opts.mtu.toString(), Modifier.weight(1f))
                MiniStat("اسکن", opts.scanMode.label, Modifier.weight(1.4f))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(RixColors.Surface.copy(alpha = 0.8f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, color = RixColors.TextMuted, fontSize = 9.5.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = RixColors.Text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Ltr)
        )
    }
}

// ------------------------------------------------------------------ helpers

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    tint: Color = RixColors.Text,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RixColors.Surface)
            .border(1.dp, RixColors.Stroke, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() }
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = RixColors.Violet,
    unfocusedBorderColor = RixColors.Stroke,
    focusedContainerColor = RixColors.Surface,
    unfocusedContainerColor = RixColors.Surface,
    cursorColor = RixColors.Violet,
    focusedLabelColor = RixColors.Violet,
    unfocusedLabelColor = RixColors.TextMuted,
    focusedTextColor = RixColors.Text,
    unfocusedTextColor = RixColors.Text
)

@Composable
private fun TextField(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label, fontSize = 11.5.sp) },
        placeholder = {
            Text(
                hint,
                color = RixColors.TextMuted.copy(alpha = 0.55f),
                fontSize = 12.sp,
                style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Ltr)
            )
        },
        textStyle = androidx.compose.ui.text.TextStyle(
            color = RixColors.Text,
            fontSize = 13.sp,
            textDirection = TextDirection.Ltr
        ),
        colors = fieldColors()
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    var local by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = local,
        onValueChange = { text ->
            if (text.length <= 6 && text.all { it.isDigit() }) {
                local = text
                onChange(text)
            }
        },
        modifier = modifier,
        singleLine = true,
        label = { Text(label, fontSize = 11.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = RixColors.Text,
            fontSize = 13.sp,
            textDirection = TextDirection.Ltr
        ),
        colors = fieldColors()
    )
}