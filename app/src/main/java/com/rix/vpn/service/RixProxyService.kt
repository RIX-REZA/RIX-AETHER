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
            RixRuntime.startCore(this, options)
            if (!RixRuntime.awaitSocks(options.bindPort)) {
                throw IllegalStateException(getString(R.string.error_socks_timeout))
            }
            RixRuntime.markRunning(options.bindPort, tun2socks = false)
            startForegroundSafely(getString(R.string.notif_proxy_running, options.bindPort))
            LogBus.i("proxy", "SOCKS5 ready on 127.0.0.1:${options.bindPort}")

            while (lifecycleScope.isActive) {
                delay(2000)
                val report = runCatching { RixRuntime.poll() }.getOrNull() ?: continue
                if (report.failed || !report.running) {
                    RixRuntime.markError(report.detail ?: getString(R.string.error_core_stopped))
                    shutdown()
                    return
                }
            }
        } catch (t: Throwable) {
            RixRuntime.markError(t.message ?: getString(R.string.error_unknown))
            shutdown()
        }
    }

    private fun shutdown() {
        RixRuntime.markStopping()
        lifecycleScope.launch {
            RixRuntime.stopCore()
            ServiceCompat.stopForeground(this@RixProxyService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            RixRuntime.markIdle()
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