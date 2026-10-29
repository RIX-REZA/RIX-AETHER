package com.rix.vpn.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap

private data class AppEntry(val pkg: String, val label: String)

/**
 * Split-tunnel app picker.
 *
 * Uses queryIntentActivities for launcher entries only, so no QUERY_ALL_PACKAGES
 * permission is needed (the manifest declares the matching <queries> intent).
 */
@Composable
fun AppPickerDialog(
    selected: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val context = LocalContext.current

    val apps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                AppEntry(pkg, info.loadLabel(pm).toString())
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    val chosen = remember { selected.toMutableSet() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(RixColors.BackgroundLift)
                .border(1.dp, RixColors.Stroke, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Text("انتخاب برنامه‌ها", color = RixColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${chosen.size} انتخاب‌شده از ${apps.size}",
                color = RixColors.TextMuted,
                fontSize = 11.5.sp
            )
            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(apps) { app ->
                    AppRow(
                        app = app,
                        checked = chosen.contains(app.pkg),
                        onToggle = {
                            if (chosen.contains(app.pkg)) chosen.remove(app.pkg) else chosen.add(app.pkg)
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton("انصراف", RixColors.Surface, RixColors.Text, Modifier.weight(1f), onDismiss)
                DialogButton(
                    "تأیید",
                    RixColors.Violet.copy(alpha = 0.22f),
                    RixColors.Text,
                    Modifier.weight(1f)
                ) { onConfirm(chosen.toList()) }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(app.pkg) {
        runCatching {
            context.packageManager.getApplicationIcon(app.pkg)
                .toBitmap(48, 48)
                .asImageBitmap()
        }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(30.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(RixColors.SurfaceHigh)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, color = RixColors.Text, fontSize = 13.sp, maxLines = 1)
            Text(app.pkg, color = RixColors.TextMuted, fontSize = 9.5.sp, maxLines = 1)
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = RixColors.Violet,
                uncheckedColor = RixColors.Stroke,
                checkmarkColor = RixColors.Background
            )
        )
    }
}

@Composable
private fun DialogButton(
    label: String,
    background: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, RixColors.Stroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = contentColor, fontSize = 13.5.sp)
    }
}