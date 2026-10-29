package com.rix.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.rix.vpn.core.NativeBridge
import com.rix.vpn.core.RixMode
import com.rix.vpn.core.RixOptions
import com.rix.vpn.core.RixRuntime
import com.rix.vpn.data.LogBus
import com.rix.vpn.data.Prefs
import com.rix.vpn.service.RixProxyService
import com.rix.vpn.service.RixVpnService
import com.rix.vpn.ui.AppPickerDialog
import com.rix.vpn.ui.HomeScreen
import com.rix.vpn.ui.LogScreen
import com.rix.vpn.ui.OptionsScreen
import com.rix.vpn.ui.RixTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RixTheme {
                RixRoot()
            }
        }
    }
}

private enum class Screen { HOME, OPTIONS, LOGS }

@Composable
private fun RixRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context.applicationContext) }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var options by remember { mutableStateOf(RixOptions()) }
    var diagnostics by remember { mutableStateOf("در حال بررسی کتابخانه‌های بومی…") }
    var showPicker by remember { mutableStateOf(false) }

    val runtimeState by RixRuntime.state.collectAsState()
    val logLines by LogBus.lines.collectAsState()

    // Load persisted options once.
    LaunchedEffect(Unit) {
        options = prefs.read()
    }

    // Probe the native libraries once, off the composition thread.
    LaunchedEffect(Unit) {
        diagnostics = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            NativeBridge.diagnostics()
        }
    }

    // Notifications are required for the foreground service to be visible on 13+.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val startTunnel = {
        RixVpnService.start(context, options)
    }

    // The system shows its own consent dialog the first time a VPN app runs.
    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startTunnel()
        } else {
            Toast.makeText(context, "اجازهٔ VPN داده نشد", Toast.LENGTH_LONG).show()
            LogBus.e("vpn", "permission denied by the user")
        }
    }

    val onToggle: () -> Unit = {
        when (runtimeState.phase) {
            RixRuntime.Phase.RUNNING, RixRuntime.Phase.STARTING -> {
                if (options.mode == RixMode.TUNNEL) {
                    RixVpnService.stop(context)
                } else {
                    RixProxyService.stop(context)
                }
            }

            else -> {
                if (options.mode == RixMode.TUNNEL) {
                    val prepare = VpnService.prepare(context)
                    if (prepare != null) vpnPermission.launch(prepare) else startTunnel()
                } else {
                    RixProxyService.start(context, options)
                }
            }
        }
    }

    val onOptionsChange: (RixOptions) -> Unit = { updated ->
        options = updated
        scope.launch { prefs.save(updated) }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            options = options,
            state = runtimeState,
            diagnostics = diagnostics,
            onModeChange = { mode -> onOptionsChange(options.copy(mode = mode)) },
            onToggle = onToggle,
            onOpenOptions = { screen = Screen.OPTIONS },
            onOpenLogs = { screen = Screen.LOGS }
        )

        Screen.OPTIONS -> {
            BackHandler { screen = Screen.HOME }
            OptionsScreen(
                options = options,
                onOptionsChange = onOptionsChange,
                onBack = { screen = Screen.HOME },
                onPickApps = { showPicker = true }
            )
        }

        Screen.LOGS -> {
            BackHandler { screen = Screen.HOME }
            LogScreen(
                lines = logLines,
                onBack = { screen = Screen.HOME },
                onClear = { LogBus.clear() }
            )
        }
    }

    if (showPicker) {
        AppPickerDialog(
            selected = options.splitApps,
            onDismiss = { showPicker = false },
            onConfirm = { apps ->
                showPicker = false
                onOptionsChange(options.copy(splitApps = apps))
            }
        )
    }
}