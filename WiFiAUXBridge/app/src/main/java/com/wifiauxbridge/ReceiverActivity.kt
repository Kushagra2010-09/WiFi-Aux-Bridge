package com.wifiauxbridge

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
    private var autoCheck: CheckBox? = null
    private var suppressAutoListener = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

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

        addAutoStartControl()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (autoStartEnabled()) startWatchService()
    }

    // ------------------------------------------------------------ auto-start on AUX cable

    private fun autoStartEnabled(): Boolean =
        getSharedPreferences("rx_state", MODE_PRIVATE).getBoolean("auto", false)

    private fun setAutoStartEnabled(on: Boolean) {
        getSharedPreferences("rx_state", MODE_PRIVATE).edit().putBoolean("auto", on).apply()
    }

    private fun startWatchService() {
        val intent = Intent(this, ReceiverService::class.java).apply { action = ReceiverService.ACTION_WATCH }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun addAutoStartControl() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val check = CheckBox(this).apply {
            text = "Auto-start when an AUX cable is plugged in"
            isChecked = autoStartEnabled()
            setTextColor(Color.parseColor("#F2F4F8"))
            typeface = Typeface.DEFAULT_BOLD
        }
        val hint = TextView(this).apply {
            text = "Keeps a small notification running and starts listening the moment you plug the cable " +
                "into this phone. It pauses when you unplug, or after 10 minutes with no transmitter. " +
                "After restarting the phone, open this app once. Untick it to start manually without a cable."
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 12f
        }
        check.setOnCheckedChangeListener { _, checked ->
            if (suppressAutoListener) return@setOnCheckedChangeListener
            setAutoStartEnabled(checked)
            if (checked) startWatchService() else boundService?.disableAutoWatch()
        }
        autoCheck = check
        card.addView(check)
        card.addView(hint)
        val parent = binding.tvLog.parent as LinearLayout
        parent.addView(card, parent.indexOfChild(binding.tvLog))
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
            // STOP switches everything off, including the AUX auto-start.
            setAutoStartEnabled(false)
            suppressAutoListener = true
            autoCheck?.isChecked = false
            suppressAutoListener = false
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
