package com.wifiauxbridge

/** Audio quality presets. Sample rate / channel count drive both capture and codec bitrate. */
enum class QualityPreset(val label: String, val sampleRate: Int, val channels: Int) {
    LOW("Low", 16000, 1),
    MEDIUM("Medium", 32000, 1),
    HIGH("High", 48000, 2);

    /** Approximate ADPCM bitrate: sampleRate * channels * 4 bits/sample. */
    fun approxBitrateBps(): Int = sampleRate * channels * 4

    companion object {
        fun fromOrdinalSafe(i: Int): QualityPreset = values().getOrElse(i) { HIGH }
    }
}

enum class ConnState {
    IDLE, SCANNING, CONNECTING, CONNECTED, STREAMING, RECONNECTING, DISCONNECTED, ERROR
}

data class DiscoveredDevice(
    val ip: String,
    val tcpPort: Int,
    val pairingCode: String
)

data class StreamStats(
    val state: ConnState = ConnState.IDLE,
    val statusMessage: String = "",
    val latencyMs: Long = -1,
    val bitrateKbps: Int = 0,
    val level: Int = 0,
    val bufferPercent: Int = 0,
    val peerAddress: String = ""
)
