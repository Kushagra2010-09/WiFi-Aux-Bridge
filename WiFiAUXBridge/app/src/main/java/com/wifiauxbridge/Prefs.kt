package com.wifiauxbridge

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("wifi_aux_bridge_prefs", Context.MODE_PRIVATE)

    var quality: QualityPreset
        get() = QualityPreset.fromOrdinalSafe(sp.getInt(KEY_QUALITY, QualityPreset.HIGH.ordinal))
        set(value) = sp.edit().putInt(KEY_QUALITY, value.ordinal).apply()

    var bufferMs: Int
        get() = sp.getInt(KEY_BUFFER_MS, DEFAULT_BUFFER_MS)
        set(value) = sp.edit().putInt(KEY_BUFFER_MS, value).apply()

    var lowLatency: Boolean
        get() = sp.getBoolean(KEY_LOW_LATENCY, false)
        set(value) = sp.edit().putBoolean(KEY_LOW_LATENCY, value).apply()

    var autoReconnect: Boolean
        get() = sp.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    var tcpPort: Int
        get() = sp.getInt(KEY_TCP_PORT, DEFAULT_TCP_PORT)
        set(value) = sp.edit().putInt(KEY_TCP_PORT, value).apply()

    fun effectiveBufferMs(): Int = if (lowLatency) minOf(bufferMs, 80) else bufferMs

    companion object {
        private const val KEY_QUALITY = "quality"
        private const val KEY_BUFFER_MS = "buffer_ms"
        private const val KEY_LOW_LATENCY = "low_latency"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_TCP_PORT = "tcp_port"

        const val DEFAULT_BUFFER_MS = 150
        const val DEFAULT_TCP_PORT = 45678
        const val UDP_DISCOVERY_PORT = 45679
    }
}
