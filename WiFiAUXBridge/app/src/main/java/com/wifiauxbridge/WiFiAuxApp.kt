package com.wifiauxbridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class WiFiAuxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Wi-Fi AUX Bridge streaming",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows the ongoing audio bridge status"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "wifi_aux_bridge_stream"
    }
}
