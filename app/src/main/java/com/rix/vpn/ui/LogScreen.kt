package com.rix.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogScreen(
    lines: List<String>,
    onBack: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Follow the tail as new lines arrive.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    RixSurface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
                .padding(top = 28.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButtonBox(Icons.Rounded.ArrowForward, "بازگشت", onBack)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("گزارش زنده", color = RixColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("${lines.size} خط", color = RixColors.TextMuted, fontSize = 11.sp)
                }
                IconButtonBox(Icons.Rounded.ContentCopy, "کپی") {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("RIX log", lines.joinToString("\n")))
                }
                Spacer(Modifier.width(8.dp))
                IconButtonBox(Icons.Rounded.Delete, "پاک‌کردن", onClear)
            }

            Spacer(Modifier.padding(top = 14.dp))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(RixColors.Surface.copy(alpha = 0.7f))
                    .border(1.dp, RixColors.Stroke, RoundedCornerShape(18.dp))
            ) {
                if (lines.isEmpty()) {
                    Text(
                        text = "هنوز خطی ثبت نشده",
                        color = RixColors.TextMuted,
                        fontSize = 12.5.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        items(lines) { line ->
                            Text(
                                text = line,
                                color = when {
                                    line.contains("خطا") -> RixColors.Danger
                                    line.contains("[hev]") -> RixColors.Teal
                                    line.contains("[core]") -> RixColors.Violet
                                    else -> RixColors.Text.copy(alpha = 0.88f)
                                },
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth(),
                                style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Ltr)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconButtonBox(
    icon: ImageVector,
    description: String,
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
        Icon(icon, contentDescription = description, tint = RixColors.Text, modifier = Modifier.size(18.dp))
    }
}