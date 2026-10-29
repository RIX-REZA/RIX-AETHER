package com.rix.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.rix.vpn.data.LogBus

class RixApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        LogBus.i("RIX", "app started (${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT})")
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_status_desc)
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