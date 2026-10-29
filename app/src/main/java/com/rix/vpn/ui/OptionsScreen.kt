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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Apps
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(RixColors.Surface)
                        .border(1.dp, RixColors.Stroke, RoundedCornerShape(12.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.ArrowForward,
                        contentDescription = "بازگشت",
                        tint = RixColors.Text,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("تنظیمات", color = RixColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "همهٔ گزینه‌های هسته در دسترس است",
                        color = RixColors.TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---------------------------------------------------- transport
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("مسیر اتصال (Transport)")
                    ChipFlow {
                        Transport.entries.forEach { transport ->
                            RixChip(
                                label = transport.label,
                                selected = draft.transport == transport,
                                onClick = { draft = draft.copy(transport = transport) }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    SectionTitle("حالت جست‌وجوی گیت‌وی (Scan)")
                    ChipFlow {
                        ScanMode.entries.forEach { mode ->
                            RixChip(
                                label = mode.label,
                                selected = draft.scanMode == mode,
                                onClick = { draft = draft.copy(scanMode = mode) }
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    SettingSwitch(
                        title = "Ironclad",
                        subtitle = "هر کاندید فقط پس از عبور ترافیک واقعی پذیرفته می‌شود",
                        checked = draft.ironclad,
                        onCheckedChange = { draft = draft.copy(ironclad = it) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------------- network
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("شبکه")

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

                    Spacer(Modifier.height(12.dp))
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
            }

            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------------ advanced
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("پیشرفته")

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
                        hint = "خالی = پیش‌فرض هسته"
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
                        hint = "دامنه/آدرس‌هایی که مستقیم بروند"
                    ) { draft = draft.copy(routeDirect = it) }

                    Spacer(Modifier.height(10.dp))
                    TextField(
                        label = "route-block",
                        value = draft.routeBlock,
                        hint = "دامنه/آدرس‌هایی که مسدود شوند"
                    ) { draft = draft.copy(routeBlock = it) }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------------ behaviour
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("رفتار اتصال")

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
                        subtitle = "استفاده از آخرین گیت‌وی کارآمد بدون اسکن تازه",
                        checked = draft.quickReconnect,
                        onCheckedChange = { draft = draft.copy(quickReconnect = it) }
                    )

                    SettingSwitch(
                        title = "Fragment کردن ClientHello",
                        subtitle = "عبور از DPI روی مسیر HTTP/2 و فراخوانی‌های WARP",
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
                                hint = "میلی‌ثانیه",
                                modifier = Modifier.weight(1f)
                            ) { draft = draft.copy(fragmentDelay = it) }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
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

                    Spacer(Modifier.height(12.dp))
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
            }

            Spacer(Modifier.height(12.dp))

            // ---------------------------------------------------- split tun
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("اسپلیت تانل (فقط حالت تونل)")
                    ChipFlow {
                        SplitMode.entries.forEach { mode ->
                            RixChip(
                                label = mode.label,
                                selected = draft.splitMode == mode,
                                onClick = { draft = draft.copy(splitMode = mode) }
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    if (draft.splitMode == SplitMode.ALL) {
                        SettingSwitch(
                            title = "خود اپ از تونل مستثنی شود",
                            subtitle = "لازم است؛ در غیر این صورت سوکت‌های هسته داخل تونل گیر می‌کنند",
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
                                text = "انتخاب برنامه‌ها",
                                color = RixColors.Text,
                                fontSize = 13.5.sp,
                                modifier = Modifier.weight(1f)
                            )
                            MonoText("${draft.splitApps.size} انتخاب‌شده", RixColors.TextMuted, 12)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // --------------------------------------------------------- extra
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("آرگومان‌های دلخواه هسته")
                    Text(
                        "هر فلگ دیگری از هسته را اینجا بنویس؛ آخر اعمال می‌شود.",
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
            }

            Spacer(Modifier.height(24.dp))
        }
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