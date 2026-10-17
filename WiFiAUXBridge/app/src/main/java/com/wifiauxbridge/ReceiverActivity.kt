package com.wifiauxbridge

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wifiauxbridge.databinding.ActivityReceiverBinding
import com.wifiauxbridge.net.NetUtils
import com.wifiauxbridge.service.ReceiverService

class ReceiverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiverBinding

    private var boundService: ReceiverService? = null
    private var bound = false
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as ReceiverService.LocalBinder).getService()
            boundService = svc
            bound = true
            binding.tvPairingCode.text = svc.pairingCode.toCharArray().joinToString(" ")
            svc.stats.observe(this@ReceiverActivity) { stats -> renderStats(stats) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvBack.setOnClickListener { finish() }
        binding.tvLocalIp.text = NetUtils.getLocalIpAddress() ?: "Not connected to Wi-Fi"
        binding.btnStartReceiver.setOnClickListener { onStartStopClicked() }
        binding.btnHotspot.setOnClickListener { toggleHotspot() }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ReceiverService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        binding.tvLocalIp.text = NetUtils.getLocalIpAddress() ?: "Not connected to Wi-Fi"
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hotspotReservation?.close()
        hotspotReservation = null
    }

    // ------------------------------------------------------------ start/stop

    private fun onStartStopClicked() {
        val state = boundService?.stats?.value?.state
        val active = state != null && state != ConnState.IDLE
        if (active) {
            boundService?.requestStop()
        } else {
            val intent = Intent(this, ReceiverService::class.java).apply { action = ReceiverService.ACTION_START }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    // ------------------------------------------------------------ hotspot

    private fun toggleHotspot() {
        if (hotspotReservation != null) {
            hotspotReservation?.close()
            hotspotReservation = null
            binding.btnHotspot.text = getString(R.string.create_hotspot)
            binding.tvHotspotInfo.text = "Hotspot stopped."
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            openSystemHotspotSettings()
            return
        }
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                @Suppress("DEPRECATION")
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    val ssid: String?
                    val password: String?
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val cfg = reservation.softApConfiguration
                        ssid = cfg.ssid
                        password = cfg.passphrase
                    } else {
                        val cfg = reservation.wifiConfiguration
                        ssid = cfg?.SSID
                        password = cfg?.preSharedKey
                    }
                    binding.tvHotspotInfo.text =
                        "Hotspot active. On the other phone, join Wi-Fi network \"$ssid\" (password \"$password\"), then use Scan/Connect on the transmitter."
                    binding.btnHotspot.text = getString(R.string.stop_hotspot)
                    binding.tvLocalIp.text = NetUtils.getLocalIpAddress() ?: "—"
                }

                override fun onStopped() {
                    hotspotReservation = null
                    binding.btnHotspot.text = getString(R.string.create_hotspot)
                }

                override fun onFailed(reason: Int) {
                    hotspotReservation = null
                    binding.tvHotspotInfo.text = getString(R.string.hotspot_not_supported)
                    binding.btnHotspot.text = getString(R.string.create_hotspot)
                }
            }, Handler(mainLooper))
        } catch (e: Exception) {
            binding.tvHotspotInfo.text = getString(R.string.hotspot_not_supported)
            openSystemHotspotSettings()
        }
    }

    private fun openSystemHotspotSettings() {
        try {
            startActivity(Intent("android.settings.WIFI_TETHER_SETTINGS"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                binding.tvHotspotInfo.text = getString(R.string.hotspot_not_supported)
            }
        }
    }

    // ------------------------------------------------------------ UI state

    private fun renderStats(stats: StreamStats) {
        binding.tvStatus.text = stats.statusMessage.ifEmpty { stats.state.name }
        val color = when (stats.state) {
            ConnState.STREAMING, ConnState.CONNECTED -> "#2ECC71"
            ConnState.CONNECTING, ConnState.RECONNECTING -> "#F5A623"
            ConnState.ERROR -> "#FF5C5C"
            else -> "#9AA4B2"
        }
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
        binding.bufferMeter.progress = stats.bufferPercent
        binding.tvLatency.text = if (stats.latencyMs >= 0) "${stats.latencyMs} ms" else "— ms"
        binding.tvBitrate.text = "${stats.bitrateKbps} kbps"
        binding.tvConnectedClient.text =
            if (stats.peerAddress.isNotEmpty()) getString(R.string.connected_client, stats.peerAddress) else ""

        val active = stats.state != ConnState.IDLE
        binding.btnStartReceiver.text =
            if (active) getString(R.string.stop_receiver) else getString(R.string.start_receiver)
        binding.btnStartReceiver.setBackgroundResource(
            if (active) R.drawable.bg_button_danger else R.drawable.bg_button_primary
        )
    }
}
