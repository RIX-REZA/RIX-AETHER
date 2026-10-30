package com.rix.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
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
import com.rix.vpn.data.LangPref
import com.rix.vpn.data.ThemeMode
import kotlinx.coroutines.delay

/**
 * Settings, in four groups instead of nine.
 *
 * The previous screen stacked a summary, five preset rows and six expandable
 * cards: the common controls were spread across the full height of the phone
 * and the screen had to be scrolled to reach any of them. This version keeps
 * the same knobs, with the same defaults, but groups them so that the whole of
 * the screen that matters fits on one page:
 *
 *  1. appearance -- the two choices that change the app, not the tunnel;
 *  2. presets     -- one row of chips, not five stacked cards;
 *  3. connection  -- transport, scan and the two switches that affect both;
 *  4. network     -- port, MTU, DNS, address family and split tunnelling;
 *  5. behaviour   -- reconnect, keepalive, fragmentation, power and logs;
 *  6. advanced    -- the text fields, folded away.
 *
 * Every hint was cut to the length of a line: they used to be paragraphs, and a
 * paragraph under every switch is what made the list feel long.
 */
@Composable
fun OptionsScreen(
    options: RixOptions,
    lang: LangPref,
    theme: ThemeMode,
    onLangChange: (LangPref) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onOptionsChange: (RixOptions) -> Unit,
    onBack: () -> Unit,
    onPickApps: () -> Unit
) {
    val s = LocalStrings.current
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
                .padding(top = 26.dp, bottom = 30.dp)
        ) {
            // ---------------------------------------------------- header
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(
                    Icons.Rounded.ArrowForward,
                    s.s("بازگشت", "Back"),
                    onClick = onBack
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        s.s("تنظیمات", "Settings"),
                        color = RixColors.Text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        s.s("پیکربندی هسته", "Core configuration"),
                        color = RixColors.TextMuted,
                        fontSize = 11.sp
                    )
                }
                RoundIconButton(
                    Icons.Rounded.RestartAlt,
                    s.s("بازگشت به پیش‌فرض", "Restore defaults"),
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

            // ------------------------------------------------ appearance
            SettingCard {
                SectionTitle(s.s("زبان", "Language"))
                SegmentedRow(
                    options = listOf(LangPref.FA.labelEn, LangPref.EN.labelEn),
                    selectedIndex = if (lang == LangPref.FA) 0 else 1,
                    onSelect = { index ->
                        onLangChange(if (index == 0) LangPref.FA else LangPref.EN)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                SectionTitle(s.s("پوسته", "Theme"))
                SegmentedRow(
                    options = ThemeMode.entries.map {
                        if (s.en) it.labelEn else it.label
                    },
                    selectedIndex = ThemeMode.entries.indexOf(theme),
                    onSelect = { index -> onThemeChange(ThemeMode.entries[index]) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(14.dp))

            // --------------------------------------------------- presets
            SectionTitle(s.s("پیکربندی آماده", "Presets"))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets().forEach { preset ->
                    PresetChip(
                        preset = preset,
                        active = preset.matches(draft),
                        onClick = { draft = preset.configure(draft) }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ------------------------------------------------ connection
            ExpandableCard(
                title = s.s("اتصال", "Connection"),
                subtitle = "${draft.transport.label(s)} • ${draft.scanMode.label(s)}",
                icon = Icons.Rounded.Route,
                initiallyExpanded = true,
                accent = RixColors.Violet
            ) {
                SectionTitle(s.s("پروتکل", "Protocol"))
                ChipFlow {
                    Transport.entries.forEach { transport ->
                        RixChip(
                            label = transport.label(s),
                            selected = draft.transport == transport,
                            onClick = { draft = draft.copy(transport = transport) }
                        )
                    }
                }
                Text(
                    text = draft.transport.desc(s),
                    color = RixColors.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )

                Spacer(Modifier.height(16.dp))
                SectionTitle(s.s("جست‌وجوی گیت‌وی", "Gateway scan"))
                ChipFlow {
                    ScanMode.entries.forEach { mode ->
                        RixChip(
                            label = mode.label(s),
                            selected = draft.scanMode == mode,
                            onClick = { draft = draft.copy(scanMode = mode) }
                        )
                    }
                }

                SettingSwitch(
                    title = s.s("Ironclad", "Ironclad"),
                    subtitle = s.s(
                        "هر کاندید فقط با ترافیک واقعی پذیرفته می‌شود",
                        "Accept a candidate only after real traffic"
                    ),
                    checked = draft.ironclad,
                    onCheckedChange = { draft = draft.copy(ironclad = it) }
                )

                SettingSwitch(
                    title = s.s("چرخش خودکار پروتکل", "Automatic fallback"),
                    subtitle = s.s(
                        "اگر پروتکل انتخابی وصل نشد، بقیه امتحان می‌شوند",
                        "If the chosen protocol fails, the others are tried"
                    ),
                    checked = draft.autoFallback,
                    onCheckedChange = { draft = draft.copy(autoFallback = it) }
                )
            }

            Spacer(Modifier.height(10.dp))

            // --------------------------------------------------- network
            ExpandableCard(
                title = s.s("شبکه", "Network"),
                subtitle = "${s.s("پورت", "port")} ${draft.bindPort} • MTU ${draft.mtu} • ${draft.ipFamily.label(s)}",
                icon = Icons.Rounded.Wifi,
                accent = RixColors.Teal
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        label = s.s("پورت SOCKS5", "SOCKS5 port"),
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
                    label = s.s("DNS (با کاما جدا کن)", "DNS (comma separated)"),
                    value = draft.dns,
                    hint = "1.1.1.1,1.0.0.1"
                ) { draft = draft.copy(dns = it) }

                Spacer(Modifier.height(14.dp))
                SectionTitle(s.s("خانوادهٔ آدرس", "Address family"))
                ChipFlow {
                    IpFamily.entries.forEach { family ->
                        RixChip(
                            label = family.label(s),
                            selected = draft.ipFamily == family,
                            onClick = { draft = draft.copy(ipFamily = family) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionTitle(s.s("اسپلیت تانل", "Split tunnel"))
                Text(
                    s.s(
                        "فقط در «تونل کامل» اثر دارد.",
                        "Only applies to the full tunnel."
                    ),
                    color = RixColors.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))

                ChipFlow {
                    SplitMode.entries.forEach { mode ->
                        RixChip(
                            label = mode.label(s),
                            selected = draft.splitMode == mode,
                            onClick = { draft = draft.copy(splitMode = mode) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (draft.splitMode == SplitMode.ALL) {
                    SettingSwitch(
                        title = s.s("خود اپ از تونل مستثنی شود", "Exclude RIX itself"),
                        subtitle = s.s(
                            "لازم است؛ در غیر این صورت سوکت‌های هسته در تونل گیر می‌کنند",
                            "Required; otherwise the core's sockets loop back into the tunnel"
                        ),
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
                            text = s.s("انتخاب برنامه‌ها", "Choose apps"),
                            color = RixColors.Text,
                            fontSize = 13.5.sp,
                            modifier = Modifier.weight(1f)
                        )
                        MonoText(
                            s.s(
                                "${draft.splitApps.size} انتخاب‌شده",
                                "${draft.splitApps.size} selected"
                            ),
                            RixColors.TextMuted,
                            12
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ------------------------------------------------- behaviour
            ExpandableCard(
                title = s.s("رفتار", "Behaviour"),
                subtitle = s.s(
                    "اتصال مجدد ${draft.reconnectSecs}ث • ${draft.perf.label(s)}",
                    "reconnect ${draft.reconnectSecs}s • ${draft.perf.label(s)}"
                ),
                icon = Icons.Rounded.Speed,
                accent = RixColors.Teal
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        label = s.s("تأخیر اتصال مجدد (ث)", "Reconnect delay (s)"),
                        value = draft.reconnectSecs.toString(),
                        modifier = Modifier.weight(1f)
                    ) { text ->
                        text.toIntOrNull()?.let { draft = draft.copy(reconnectSecs = it.coerceIn(0, 600)) }
                    }
                    NumberField(
                        label = s.s("Keepalive (ث)", "Keepalive (s)"),
                        value = draft.keepalive.toString(),
                        modifier = Modifier.weight(1f)
                    ) { text ->
                        text.toIntOrNull()?.let { draft = draft.copy(keepalive = it.coerceIn(0, 3600)) }
                    }
                }

                SettingSwitch(
                    title = s.s("اتصال مجدد سریع", "Fast reconnect"),
                    subtitle = s.s(
                        "بدون اسکن تازه، از گیت‌وی قبلی",
                        "Reuse the last gateway, no fresh scan"
                    ),
                    checked = draft.quickReconnect,
                    onCheckedChange = { draft = draft.copy(quickReconnect = it) }
                )

                SettingSwitch(
                    title = s.s("Fragment کردن ClientHello", "Fragment the ClientHello"),
                    subtitle = s.s(
                        "عبور از DPI روی HTTP/2 و WARP",
                        "Gets past DPI on HTTP/2 and WARP"
                    ),
                    checked = draft.fragment,
                    onCheckedChange = { draft = draft.copy(fragment = it) }
                )

                if (draft.fragment) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextField(
                            label = "fragment-size",
                            value = draft.fragmentSize,
                            hint = s.s("مثلاً 40", "e.g. 40"),
                            modifier = Modifier.weight(1f)
                        ) { draft = draft.copy(fragmentSize = it) }
                        TextField(
                            label = "fragment-delay",
                            value = draft.fragmentDelay,
                            hint = s.s("میلی‌ثانیه", "milliseconds"),
                            modifier = Modifier.weight(1f)
                        ) { draft = draft.copy(fragmentDelay = it) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionTitle(s.s("پروفایل منابع", "Power profile"))
                ChipFlow {
                    PerfProfile.entries.forEach { profile ->
                        RixChip(
                            label = profile.label(s),
                            selected = draft.perf == profile,
                            onClick = { draft = draft.copy(perf = profile) }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionTitle(s.s("سطح لاگ", "Log level"))
                ChipFlow {
                    LogLevel.entries.forEach { level ->
                        RixChip(
                            label = level.label(s),
                            selected = draft.logLevel == level,
                            onClick = { draft = draft.copy(logLevel = level) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // -------------------------------------------------- advanced
            ExpandableCard(
                title = s.s("پیشرفته", "Advanced"),
                subtitle = s.s(
                    "گیت‌وی دستی، پروکسی بالادستی، ECH، مسیرها",
                    "manual gateway, upstream, ECH, routes"
                ),
                icon = Icons.Rounded.Tune,
                accent = RixColors.Warning
            ) {
                TextField(
                    label = s.s("کشور خروجی (exit-loc)", "Exit country (exit-loc)"),
                    value = draft.exitLoc,
                    hint = s.s("مثلاً DE یا IR", "e.g. DE or IR")
                ) { draft = draft.copy(exitLoc = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = s.s("peer دستی (ip:port)", "Manual peer (ip:port)"),
                    value = draft.peer,
                    hint = "188.114.96.1:2408"
                ) { draft = draft.copy(peer = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = "MASQUE SNI",
                    value = draft.masqueSni,
                    hint = s.s("خالی = پیش‌فرض هسته", "empty = core default")
                ) { draft = draft.copy(masqueSni = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = s.s("پروکسی بالادستی", "Upstream proxy"),
                    value = draft.upstreamProxy,
                    hint = "socks5://127.0.0.1:1080"
                ) { draft = draft.copy(upstreamProxy = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = s.s("ECH (auto یا base64)", "ECH (auto or base64)"),
                    value = draft.ech,
                    hint = s.s("خالی = خاموش", "empty = off")
                ) { draft = draft.copy(ech = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = "route-direct",
                    value = draft.routeDirect,
                    hint = s.s("دامنه‌هایی که مستقیم بروند", "domains that go out directly")
                ) { draft = draft.copy(routeDirect = it) }

                Spacer(Modifier.height(10.dp))
                TextField(
                    label = "route-block",
                    value = draft.routeBlock,
                    hint = s.s("دامنه‌هایی که مسدود شوند", "domains that are blocked")
                ) { draft = draft.copy(routeBlock = it) }

                Spacer(Modifier.height(14.dp))
                SectionTitle(s.s("آرگومان‌های دلخواه هسته", "Extra core arguments"))
                Text(
                    s.s(
                        "هر فلگ دیگری از هسته را اینجا بنویس؛ آخر اعمال می‌شود.",
                        "Any other flag the core accepts; applied last."
                    ),
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
                text = s.s(
                    "تغییرات به‌صورت خودکار ذخیره می‌شوند",
                    "Changes are saved automatically"
                ),
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
    val labelFa: String,
    val labelEn: String,
    val icon: ImageVector,
    val accent: Color,
    val configure: (RixOptions) -> RixOptions,
    val matches: (RixOptions) -> Boolean
)

@Composable
private fun presets(): List<Preset> = listOf(
    Preset(
        labelFa = "پیش‌فرض",
        labelEn = "Default",
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
        labelFa = "ایران",
        labelEn = "Iran",
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
        labelFa = "سریع",
        labelEn = "Fast",
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
        labelFa = "ضد DPI",
        labelEn = "Anti-DPI",
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
        labelFa = "کم‌مصرف",
        labelEn = "Low power",
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

/**
 * One preset as a chip.
 *
 * Five stacked rows became five chips on a single scrolling line: the whole
 * point of a preset is a one-tap choice, and a row that carries a title, a
 * two-line hint and a badge spends a quarter of the screen saying what the
 * title already said.
 */
@Composable
private fun PresetChip(preset: Preset, active: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
    val accent = preset.accent
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (active) accent.copy(alpha = if (RixColors.isDark) 0.16f else 0.10f)
                else RixColors.Surface.copy(alpha = 0.75f)
            )
            .border(
                1.dp,
                if (active) accent.copy(alpha = 0.7f) else RixColors.Stroke,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            preset.icon,
            contentDescription = null,
            tint = if (active) accent else RixColors.TextMuted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = s.s(preset.labelFa, preset.labelEn),
            color = if (active) RixColors.Text else RixColors.TextMuted,
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// ------------------------------------------------------------------ pieces

/** A plain card, for the group that is not collapsible. */
@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(RixColors.Surface.copy(alpha = 0.8f))
            .border(1.dp, RixColors.Stroke.copy(alpha = 0.75f), RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

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