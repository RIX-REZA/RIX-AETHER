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
import androidx.compose.runtime.rememberUpdatedState
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
import com.rix.vpn.data.ThemeMode
import com.rix.vpn.data.UiPrefs
import com.rix.vpn.service.RixProxyService
import com.rix.vpn.service.RixServices
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
            // The theme lives outside RixOptions: it is a UI preference, not a
            // core argument, so it is stored in its own DataStore and read here.
            val uiPrefs = remember { UiPrefs(applicationContext) }
            val scope = rememberCoroutineScope()
            val themeMode by uiPrefs.theme.collectAsState(initial = ThemeMode.SYSTEM)

            RixTheme(mode = themeMode) {
                RixRoot(
                    themeMode = themeMode,
                    onCycleTheme = { scope.launch { uiPrefs.saveTheme(themeMode.next()) } }
                )
            }
        }
    }
}

private enum class Screen { HOME, OPTIONS, LOGS }

/**
 * How long a teardown may take before the UI stops waiting for it.
 *
 * The graceful path is: stop the engine (up to 2.5 s), cancel the core and free
 * its slot (0.25 s), close the TUN, drop the notification, stop the service.
 * Twelve seconds is several times that, so reaching it means the teardown is
 * not coming back.
 */
private const val STOP_GIVE_UP_MS = 12_000L

@Composable
private fun RixRoot(themeMode: ThemeMode, onCycleTheme: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context.applicationContext) }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var options by remember { mutableStateOf(RixOptions()) }
    var diagnostics by remember { mutableStateOf("در حال بررسی کتابخانه‌های بومی…") }
    var showPicker by remember { mutableStateOf(false) }

    val runtimeState by RixRuntime.state.collectAsState()

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

    // Every callback below is captured in `remember` and reads live state
    // through rememberUpdatedState. Without that the lambdas get a fresh
    // identity on each recomposition, which makes HomeScreen unskippable and
    // re-runs the whole screen whenever the runtime ticks once a second.
    val latestOptions = rememberUpdatedState(options)
    val latestPhase = rememberUpdatedState(runtimeState.phase)

    // Belt and braces for the one failure the user can see but not fix from
    // inside the app: a session the UI considers over while a service still
    // holds the tunnel. It has two causes -- a stop addressed to the wrong
    // service, and a teardown that never reached its end -- and both leave the
    // key icon and the ongoing notification on screen with nothing able to
    // clear them, because the orb is inert once the phase is STOPPING.
    LaunchedEffect(runtimeState.phase) {
        when (runtimeState.phase) {
            RixRuntime.Phase.STOPPING -> {
                kotlinx.coroutines.delay(STOP_GIVE_UP_MS)
                if (RixRuntime.state.value.phase == RixRuntime.Phase.STOPPING) {
                    LogBus.e("vpn", "teardown did not finish in time")
                    RixServices.forceStopAll(context)
                }
            }

            RixRuntime.Phase.IDLE, RixRuntime.Phase.ERROR -> {
                // The TUN is released a few milliseconds after markIdle(),
                // inside the same teardown block, so wait that out before
                // concluding that the service outlived its session.
                kotlinx.coroutines.delay(1_500)
                if (RixVpnService.isUp()) {
                    LogBus.e("vpn", "a tunnel outlived its session")
                    RixServices.forceStopAll(context)
                }
            }

            else -> Unit
        }
    }

    val startTunnel: () -> Unit = remember(context) {
        {
            val opts = latestOptions.value
            // Instant feedback. The service runs on its own thread and may not
            // reach markStarting for a few hundred milliseconds; until then the
            // orb looked like a dead button and users tapped it twice.
            RixRuntime.markStarting(opts.mode, "")
            runCatching {
                if (opts.mode == RixMode.TUNNEL) {
                    RixVpnService.start(context, opts)
                } else {
                    RixProxyService.start(context, opts)
                }
            }.onFailure { error ->
                // Nothing was started, so the optimistic phase must be undone
                // or the screen would sit in «آماده‌سازی» forever.
                RixRuntime.markError(error.message ?: "شروع سرویس ناموفق بود")
            }
        }
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

    val vpnPermissionRef = rememberUpdatedState(vpnPermission)

    val onToggle: () -> Unit = remember(context) {
        {
            when (latestPhase.value) {
                // CONNECTING is cancellable: a tap while the tunnel is being
                // proven must tear the attempt down, not start a second one.
                RixRuntime.Phase.RUNNING,
                RixRuntime.Phase.STARTING,
                RixRuntime.Phase.CONNECTING -> {
                    // Both, deliberately. The mode in `options` is what the
                    // settings screen is showing, which is not necessarily the
                    // mode of the live session: switching the segmented control
                    // while connected used to send this stop to the idle service
                    // and leave the tunnel -- and its key icon -- running.
                    RixServices.stopAll(context)
                }

                // Already going down; a second stop would race the first.
                RixRuntime.Phase.STOPPING -> Unit

                else -> {
                    val opts = latestOptions.value
                    if (opts.mode == RixMode.TUNNEL) {
                        val prepare = VpnService.prepare(context)
                        if (prepare != null) vpnPermissionRef.value.launch(prepare) else startTunnel()
                    } else {
                        startTunnel()
                    }
                }
            }
        }
    }

    val onModeChange: (RixMode) -> Unit = remember {
        { mode -> options = options.copy(mode = mode) }
    }

    val onOpenOptions: () -> Unit = remember { { screen = Screen.OPTIONS } }
    val onOpenLogs: () -> Unit = remember { { screen = Screen.LOGS } }
    val onBackHome: () -> Unit = remember { { screen = Screen.HOME } }

    val onOptionsChange: (RixOptions) -> Unit = remember {
        { updated: RixOptions ->
            options = updated
            scope.launch { prefs.save(updated) }
        }
    }

    val onConfirmApps: (List<String>) -> Unit = remember {
        { apps: List<String> ->
            showPicker = false
            onOptionsChange(options.copy(splitApps = apps))
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            options = options,
            state = runtimeState,
            diagnostics = diagnostics,
            theme = themeMode,
            onCycleTheme = onCycleTheme,
            onModeChange = onModeChange,
            onToggle = onToggle,
            onOpenOptions = onOpenOptions,
            onOpenLogs = onOpenLogs
        )

        Screen.OPTIONS -> {
            BackHandler(onBack = onBackHome)
            OptionsScreen(
                options = options,
                onOptionsChange = onOptionsChange,
                onBack = onBackHome,
                onPickApps = { showPicker = true }
            )
        }

        Screen.LOGS -> {
            BackHandler(onBack = onBackHome)
            LogScreen(
                onBack = onBackHome,
                onClear = { LogBus.clear() }
            )
        }
    }

    if (showPicker) {
        AppPickerDialog(
            selected = options.splitApps,
            onDismiss = { showPicker = false },
            onConfirm = onConfirmApps
        )
    }
}