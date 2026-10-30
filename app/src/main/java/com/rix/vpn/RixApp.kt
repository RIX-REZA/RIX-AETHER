package com.rix.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.rix.vpn.data.LogBus
import com.rix.vpn.data.l10n

class RixApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        LogBus.i("RIX", "app started (${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT})")
    }

    /** Re-run when the language changes: the channel's name is user-visible. */
    fun refreshChannels() = createChannels()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                l10n(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = l10n(R.string.channel_status_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_STATUS = "rix.status"
        lateinit var instance: RixApp
            private set
    }
}