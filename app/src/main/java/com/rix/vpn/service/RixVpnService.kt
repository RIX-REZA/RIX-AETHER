package com.rix.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rix.vpn.MainActivity
import com.rix.vpn.R
import com.rix.vpn.RixApp
import com.rix.vpn.core.RixMode
import com.rix.vpn.core.RixOptions
import com.rix.vpn.core.RixRuntime
import com.rix.vpn.data.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-tunnel mode.
 *
 * Packet path: every app -> TUN (this service) -> hev userspace TCP/IP ->
 * SOCKS5 on 127.0.0.1:<port> -> Aether core -> WARP / MASQUE -> internet.
 */
class RixVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var worker: Job? = null
    private var ticker: Job? = null
    private var watch: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LogBus.i("vpn", "stop requested")
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val raw = intent.getStringExtra(EXTRA_OPTIONS)
                val options = RixOptions.fromJson(raw)
                startForegroundSafely(getString(R.string.notif_starting))
                if (worker?.isActive == true) {
                    LogBus.i("vpn", "already starting or running; ignoring duplicate start")
                    return START_NOT_STICKY
                }
                worker = scope.launch { bringUp(options) }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    // -------------------------------------------------------------- bring-up

    private suspend fun bringUp(options: RixOptions) {
        try {
            RixRuntime.markStarting(RixMode.TUNNEL, getString(R.string.state_starting))

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(options.mtu)
                .addAddress(options.tunnelIpv4, 30)
                .addRoute("0.0.0.0", 0)
                // IPv6 is captured too; leaving it unrouted would leak v6 traffic
                // outside the tunnel on dual-stack carriers.
                .addAddress(options.tunnelIpv6, 126)
                .addRoute("::", 0)

            primaryDns(options)?.let { builder.addDnsServer(it) }

            applySplitTunnelling(builder, options)

            val fd = builder.establish()
                ?: throw IllegalStateException(getString(R.string.error_no_tun))
            tun = fd
            LogBus.i("vpn", "TUN established, fd=${fd.fd}")

            val job = RixRuntime.startCore(this, options)
            LogBus.i("vpn", "core job=$job; waiting for SOCKS5 on ${options.bindPort}")

            if (!RixRuntime.awaitSocks(options.bindPort)) {
                throw IllegalStateException(getString(R.string.error_socks_timeout))
            }
            LogBus.i("vpn", "SOCKS5 is accepting connections")

            // detachFd hands ownership of the descriptor to hev; closing the
            // ParcelFileDescriptor afterwards would close the same fd twice.
            val raw = fd.detachFd()

            RixRuntime.startTun2Socks(this, options, raw) { rc ->
                LogBus.i("hev", "tun2socks exited rc=$rc")
                if (RixRuntime.state.value.phase == RixRuntime.Phase.RUNNING) {
                    scope.launch { failAndStop(getString(R.string.error_engine_stopped)) }
                }
            }

            // hev can return within milliseconds (see the SYNC_STOP note in
            // RixRuntime.startTun2Socks). Reporting "connected" before proving
            // the engine survived would show a green screen over a dead tunnel.
            if (!RixRuntime.awaitHevAlive()) {
                throw IllegalStateException(getString(R.string.error_engine_stopped))
            }

            RixRuntime.markRunning(options.bindPort, tun2socks = true)
            startForegroundSafely(getString(R.string.notif_running, options.bindPort))
            startTicker()
            startCoreWatch(options)

        } catch (t: Throwable) {
            failAndStop(t.message ?: getString(R.string.error_unknown))
        }
    }

    private fun primaryDns(options: RixOptions): String? =
        options.dns.split(',', ' ')
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) }

    /**
     * Split tunnelling.
     *
     * addAllowedApplication and addDisallowedApplication are mutually exclusive,
     * and the VPN app itself must never be captured by its own TUN — otherwise
     * the core's WARP sockets are routed back into the tunnel and deadlock.
     * Excluding our own package is what breaks that loop.
     */
    private fun applySplitTunnelling(builder: Builder, options: RixOptions) {
        val self = packageName
        when {
            options.splitMode == com.rix.vpn.core.SplitMode.ONLY && options.splitApps.isNotEmpty() -> {
                // Only the chosen apps are captured; the core stays outside by
                // simply not being on the list.
                options.splitApps.filter { it != self }.forEach {
                    runCatching { builder.addAllowedApplication(it) }
                        .onFailure { e -> LogBus.e("vpn", "allow $it: ${e.message}") }
                }
                LogBus.i("vpn", "split: only ${options.splitApps.size} app(s)")
            }

            options.splitMode == com.rix.vpn.core.SplitMode.EXCEPT && options.splitApps.isNotEmpty() -> {
                (options.splitApps + self).distinct().forEach {
                    runCatching { builder.addDisallowedApplication(it) }
                        .onFailure { e -> LogBus.e("vpn", "disallow $it: ${e.message}") }
                }
                LogBus.i("vpn", "split: all but ${options.splitApps.size} app(s)")
            }

            else -> {
                if (options.excludeSelf) {
                    runCatching { builder.addDisallowedApplication(self) }
                        .onFailure { e -> LogBus.e("vpn", "exclude self: ${e.message}") }
                }
                LogBus.i("vpn", "split: every app")
            }
        }
    }

    // ---------------------------------------------------------------- ticking

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                RixRuntime.pushStats()
                delay(1000)
            }
        }
    }

    private fun startCoreWatch(options: RixOptions) {
        watch?.cancel()
        watch = scope.launch {
            while (isActive) {
                delay(2000)
                val report = runCatching { RixRuntime.poll() }.getOrNull() ?: continue
                if (report.failed) {
                    failAndStop(report.detail ?: getString(R.string.error_core_stopped))
                    return@launch
                }
                if (!report.running) {
                    failAndStop(getString(R.string.error_core_stopped))
                    return@launch
                }
            }
        }
    }

    private suspend fun failAndStop(message: String) {
        RixRuntime.markError(message)
        runCatching {
            startForegroundSafely(getString(R.string.notif_error))
        }
        shutdown()
    }

    // --------------------------------------------------------------- shutdown

    fun shutdown() {
        RixRuntime.markStopping()
        ticker?.cancel()
        ticker = null
        // Without this a failed attempt leaves its poll loop running; it then
        // reports "core closed" over the *next* connection's error state.
        watch?.cancel()
        watch = null

        // tun2socks first: it is the only reader of the TUN fd, so stopping the
        // core before it would leave the engine spinning on dead sockets.
        RixRuntime.stopTun2Socks()

        scope.launch {
            RixRuntime.stopCore()
            withContext(Dispatchers.Main) {
                runCatching { tun?.close() }
                tun = null
                ServiceCompat.stopForeground(this@RixVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                RixRuntime.markIdle()
                stopSelf()
            }
        }
        worker?.cancel()
        worker = null
    }

    override fun onRevoke() {
        LogBus.i("vpn", "revoked by the system or another VPN")
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        LogBus.i("vpn", "service destroyed")
        runCatching { RixRuntime.stopTun2Socks() }
        runCatching { tun?.close() }
        tun = null
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------- notification

    private fun startForegroundSafely(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
        }.onFailure { LogBus.e("vpn", "startForeground: ${it.message}") }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RixVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RixApp.CHANNEL_STATUS)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_rix)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.rix.vpn.START_TUNNEL"
        const val ACTION_STOP = "com.rix.vpn.STOP_TUNNEL"
        const val EXTRA_OPTIONS = "options"

        fun start(context: Context, options: RixOptions) {
            val intent = Intent(context, RixVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_OPTIONS, options.toJson())
            // startForegroundService: on API 26+ a plain startService can be
            // refused once the activity is no longer visible.
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RixVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}