package com.rix.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rix.vpn.MainActivity
import com.rix.vpn.R
import com.rix.vpn.RixApp
import com.rix.vpn.core.RixMode
import com.rix.vpn.core.RixOptions
import com.rix.vpn.core.RixRuntime
import com.rix.vpn.core.TunnelProbe
import com.rix.vpn.data.LogBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Proxy mode.
 *
 * No VpnService, no TUN, no packet capture: the core simply serves a local
 * SOCKS5 endpoint that the user points a browser or another app at. Useful when
 * the phone already runs a VPN, or when only one app should be tunnelled.
 */
class RixProxyService : LifecycleService() {

    private var worker: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val options = RixOptions.fromJson(intent.getStringExtra(EXTRA_OPTIONS))
                startForegroundSafely(getString(R.string.notif_starting))
                if (worker?.isActive == true) return START_NOT_STICKY
                worker = lifecycleScope.launch { bringUp(options) }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun bringUp(options: RixOptions) {
        try {
            RixRuntime.markStarting(RixMode.PROXY, getString(R.string.state_starting))

            // Same chain as tunnel mode: proxy or not, the core still has to
            // reach Cloudflare, and a blocked transport fails identically.
            val chain = RixRuntime.transportChain(options, RixRuntime.rememberedTransport(this))
            LogBus.i("proxy", "transport chain: ${chain.joinToString(" → ") { it.transport.label }}")

            var winner: RixOptions? = null
            var egress: TunnelProbe.Egress? = null

            for ((index, candidate) in chain.withIndex()) {
                if (index > 0) {
                    LogBus.i("proxy", "${candidate.transport.label}: retrying after ${chain[index - 1].transport.label}")
                    runCatching { RixRuntime.stopCore() }
                    delay(200)
                }

                RixRuntime.markConnecting(
                    candidate.bindPort,
                    tun2socks = false,
                    message = getString(
                        R.string.state_trying_transport,
                        index + 1,
                        chain.size,
                        candidate.transport.label
                    )
                )
                startForegroundSafely(getString(R.string.notif_connecting))

                val listening = try {
                    RixRuntime.startCore(this, candidate)
                    RixRuntime.awaitSocks(candidate.bindPort, timeoutMs = CORE_LISTEN_MS)
                } catch (t: Throwable) {
                    LogBus.e("proxy", "${candidate.transport.label}: ${t.message}")
                    false
                }
                if (!listening) continue

                val budget = if (index == 0) FIRST_PROBE_MS else NEXT_PROBE_MS
                val result = RixRuntime.awaitTunnel(candidate.bindPort, timeoutMs = budget)
                if (result != null) {
                    winner = candidate
                    egress = result
                    break
                }
                LogBus.e("proxy", "${candidate.transport.label}: no traffic came back through the proxy")
            }

            val chosen = winner
            val verified = egress
            if (chosen == null || verified == null) {
                throw IllegalStateException(getString(R.string.error_all_transports_failed))
            }

            RixRuntime.rememberTransport(this, chosen.transport)
            LogBus.i("proxy", "SOCKS5 verified via ${chosen.transport.label} on 127.0.0.1:${chosen.bindPort}, egress ${verified.ip}")
            RixRuntime.markRunning(
                chosen.bindPort,
                tun2socks = false,
                egress = verified,
                transport = chosen.transport
            )
            startForegroundSafely(getString(R.string.notif_proxy_running, chosen.bindPort))

            while (lifecycleScope.isActive) {
                delay(2000)
                val report = runCatching { RixRuntime.poll() }.getOrNull() ?: continue
                if (report.failed || !report.running) {
                    shutdown(preserveError = report.detail ?: getString(R.string.error_core_stopped))
                    return
                }
            }
        } catch (t: Throwable) {
            shutdown(preserveError = t.message ?: getString(R.string.error_unknown))
        }
    }

    private fun shutdown(preserveError: String? = null) {
        if (preserveError != null) {
            // Published before the teardown so the reason survives it: markIdle()
            // at the end of this method used to wipe it a moment later.
            RixRuntime.markError(preserveError)
        } else {
            RixRuntime.markStopping()
        }
        lifecycleScope.launch {
            RixRuntime.stopCore()
            ServiceCompat.stopForeground(this@RixProxyService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (preserveError == null) RixRuntime.markIdle()
            stopSelf()
        }
        worker?.cancel()
        worker = null
    }

    override fun onDestroy() {
        runCatching { lifecycleScope.launch { RixRuntime.stopCore() } }
        super.onDestroy()
    }

    private fun startForegroundSafely(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
        }.onFailure { LogBus.e("proxy", "startForeground: ${it.message}") }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RixProxyService::class.java).setAction(ACTION_STOP),
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
        private const val NOTIF_ID = 1002

        /** How long the core gets to open its SOCKS5 port, per transport. */
        private const val CORE_LISTEN_MS = 15_000L
        private const val FIRST_PROBE_MS = 20_000L
        private const val NEXT_PROBE_MS = 10_000L
        const val ACTION_START = "com.rix.vpn.START_PROXY"
        const val ACTION_STOP = "com.rix.vpn.STOP_PROXY"
        const val EXTRA_OPTIONS = "options"

        fun start(context: Context, options: RixOptions) {
            val intent = Intent(context, RixProxyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_OPTIONS, options.toJson())
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RixProxyService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}