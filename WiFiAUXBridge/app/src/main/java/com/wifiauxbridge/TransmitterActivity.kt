package com.wifiauxbridge

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Toast
import android.graphics.Typeface
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import com.wifiauxbridge.audio.Adpcm
import com.wifiauxbridge.net.Protocol
import com.wifiauxbridge.net.StreamSession
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wifiauxbridge.databinding.ActivityTransmitterBinding
import com.wifiauxbridge.net.DiscoveryClient
import com.wifiauxbridge.service.TransmitterService

class TransmitterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTransmitterBinding
    private lateinit var prefs: Prefs
    private var discoveryClient: DiscoveryClient? = null

    private var boundService: TransmitterService? = null
    private var bound = false

    private var selectedIp: String? = null
    private var selectedPort: Int = Prefs.DEFAULT_TCP_PORT
    private var targetConfirmed = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as TransmitterService.LocalBinder).getService()
            boundService = svc
            bound = true
            pushVolumeSettings()
            svc.stats.observe(this@TransmitterActivity) { stats -> renderStats(stats) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            bound = false
        }
    }

    private val recordAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestProjectionAndStart()
            else Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val ip = selectedIp
            if (result.resultCode == RESULT_OK && data != null && ip != null) {
                val intent = Intent(this, TransmitterService::class.java).apply {
                    action = TransmitterService.ACTION_START
                    putExtra(TransmitterService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(TransmitterService.EXTRA_RESULT_DATA, data)
                    putExtra(TransmitterService.EXTRA_IP, ip)
                    putExtra(TransmitterService.EXTRA_PORT, selectedPort)
                }
                ContextCompat.startForegroundService(this, intent)
                bindToService()
            } else {
                Toast.makeText(this, "System audio capture permission was not granted.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransmitterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.tvBack.setOnClickListener { finish() }
        binding.etPort.setText(prefs.tcpPort.toString())

        binding.btnScan.setOnClickListener { scanForReceivers() }
        binding.btnConnect.setOnClickListener { confirmTarget() }
        binding.btnStream.setOnClickListener { onStreamButtonClicked() }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.tvLog.text = getString(R.string.capture_not_supported)
            binding.btnStream.isEnabled = false
        } else {
            binding.btnStream.isEnabled = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        addVolumeControls()
        addTestToneButton()
        scanForReceivers()
    }

    override fun onStart() {
        super.onStart()
        // Bind only if the service is already running; it is created by startForegroundService.
        bindToService()
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onDestroy() {
        discoveryClient?.stop()
        super.onDestroy()
    }

    private fun bindToService() {
        if (!bound) {
            bindService(Intent(this, TransmitterService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    // ------------------------------------------------------------ discovery

    private fun scanForReceivers() {
        binding.deviceListContainer.removeAllViews()
        binding.tvNoDevices.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.status_scanning)
        discoveryClient?.stop()
        val client = DiscoveryClient(Prefs.UDP_DISCOVERY_PORT)
        discoveryClient = client
        var found = 0
        client.scan(
            durationMs = 3000,
            onFound = { device ->
                runOnUiThread {
                    found++
                    binding.tvNoDevices.visibility = View.GONE
                    addDeviceRow(device)
                }
            },
            onFinished = {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.tvStatus.text = getString(R.string.status_idle)
                    if (found == 0) binding.tvNoDevices.visibility = View.VISIBLE
                }
            }
        )
    }

    private fun addDeviceRow(device: DiscoveredDevice) {
        val row = TextView(this).apply {
            text = "${device.ip}:${device.tcpPort}   •   code ${device.pairingCode}"
            setTextColor(Color.parseColor("#F2F4F8"))
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 28, 24, 28)
            setBackgroundResource(R.drawable.bg_list_row)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 12
            layoutParams = params
            setOnClickListener {
                binding.etIp.setText(device.ip)
                binding.etPort.setText(device.tcpPort.toString())
                confirmTarget()
            }
        }
        binding.deviceListContainer.addView(row)
    }

    // ------------------------------------------------------------ receiver volume

    private fun pushVolumeSettings() {
        val sp = getSharedPreferences("tx_ui", MODE_PRIVATE)
        boundService?.setVolumeControl(sp.getBoolean("follow", true), sp.getInt("manual", 70))
    }

    private fun addVolumeControls() {
        val sp = getSharedPreferences("tx_ui", MODE_PRIVATE)
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
        val title = TextView(this).apply {
            text = "Receiver volume"
            setTextColor(Color.parseColor("#F2F4F8"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val follow = CheckBox(this).apply {
            text = "Follow this phone's volume buttons"
            isChecked = sp.getBoolean("follow", true)
            setTextColor(Color.parseColor("#F2F4F8"))
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = sp.getInt("manual", 70)
            isEnabled = !sp.getBoolean("follow", true)
        }
        val hint = TextView(this).apply {
            text = "ON: the old phone's volume follows this phone's volume buttons (this phone's own speaker " +
                "will also be audible). OFF: use the slider, so you can set this phone's volume to 0 and " +
                "still control the old phone. Keep the old phone's own volume at maximum."
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 12f
        }
        follow.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("follow", checked).apply()
            seek.isEnabled = !checked
            pushVolumeSettings()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    sp.edit().putInt("manual", progress).apply()
                    pushVolumeSettings()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        card.addView(title)
        card.addView(follow)
        card.addView(seek)
        card.addView(hint)
        val parent = binding.tvLog.parent as LinearLayout
        parent.addView(card, parent.indexOfChild(binding.tvLog))
    }

    // ------------------------------------------------------------ test tone

    @Volatile private var toneRunning = false

    private fun addTestToneButton() {
        val btn = android.widget.Button(this).apply {
            text = "TEST TONE (should beep on receiver)"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_button_secondary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 150
            ).apply { topMargin = 24 }
            setOnClickListener { sendTestTone() }
        }
        val parent = binding.tvLog.parent as LinearLayout
        parent.addView(btn, parent.indexOfChild(binding.tvLog))
    }

    /**
     * Sends a 5-second 440 Hz beep to the receiver WITHOUT using audio capture. If this beeps
     * on the receiver, the network + receiver playback work and any silence is caused by
     * the capture side (the source app blocking capture, or the phone).
     */
    private fun sendTestTone() {
        if (toneRunning) {
            toneRunning = false
            return
        }
        if (!applyTargetFromFields()) return
        val ip = selectedIp ?: return
        val port = selectedPort
        toneRunning = true
        binding.tvStatus.text = "Sending test tone to $ip:$port…"
        thread(name = "tx-tone", isDaemon = true) {
            var msg = ""
            var socket: Socket? = null
            var session: StreamSession? = null
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ip, port), 4000)
                socket = s
                val sess = StreamSession(s, object : StreamSession.Listener {})
                session = sess
                sess.start()
                val rate = 48000
                sess.sendHello(rate, 1, Protocol.CODEC_ADPCM)
                val enc = Adpcm.Encoder(1)
                val chunk = rate / 50 // 20 ms
                val buf = ShortArray(chunk)
                val step = 2.0 * PI * 440.0 / rate
                var phase = 0.0
                var seq = 0
                var next = System.nanoTime()
                val end = System.currentTimeMillis() + 5000
                while (toneRunning && System.currentTimeMillis() < end) {
                    for (i in 0 until chunk) {
                        buf[i] = (sin(phase) * 9000).toInt().toShort()
                        phase += step
                    }
                    sess.sendAudio(seq++, chunk, enc.encode(buf, chunk, 1))
                    next += 20_000_000L
                    val sleepMs = (next - System.nanoTime()) / 1_000_000L
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                }
                msg = "Test tone finished. Did the receiver phone beep?"
            } catch (e: Exception) {
                msg = "Test tone failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                runCatching { session?.sendBye() }
                runCatching { session?.close() }
                runCatching { socket?.close() }
                toneRunning = false
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) binding.tvStatus.text = msg
            }
        }
    }

    // ------------------------------------------------------------ connect / stream

    /** Reads IP/port from the fields. Returns false (and shows a message) if invalid. */
    private fun applyTargetFromFields(): Boolean {
        val ip = binding.etIp.text.toString().trim()
        val port = binding.etPort.text.toString().toIntOrNull()
        if (ip.isEmpty() || port == null || port !in 1..65535) {
            Toast.makeText(this, "Enter the receiver's IP address and port first.", Toast.LENGTH_SHORT).show()
            return false
        }
        selectedIp = ip
        selectedPort = port
        targetConfirmed = true
        return true
    }

    /** CONNECT: checks that the receiver can actually be reached over the network. */
    private fun confirmTarget() {
        if (!applyTargetFromFields()) return
        val ip = selectedIp ?: return
        val port = selectedPort
        binding.tvStatus.text = "Checking receiver at $ip:$port…"
        thread(name = "tx-probe", isDaemon = true) {
            val error: String? = try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 3000) }
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error == null) {
                    binding.tvStatus.text = "Receiver found at $ip:$port. Now tap START STREAM."
                    binding.statusDot.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#2ECC71"))
                } else {
                    binding.tvStatus.text =
                        "Can't reach $ip:$port ($error). Is RECEIVER started on the other phone, and are both on the same Wi-Fi?"
                    binding.statusDot.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#FF5C5C"))
                }
            }
        }
    }

    private fun isActive(): Boolean {
        val state = boundService?.stats?.value?.state ?: return false
        return state != ConnState.IDLE && state != ConnState.ERROR
    }

    private fun onStreamButtonClicked() {
        if (isActive()) {
            boundService?.requestStop()
            return
        }
        if (!applyTargetFromFields()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestProjectionAndStart()
    }

    private fun requestProjectionAndStart() {
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    // ------------------------------------------------------------ UI state

    private fun renderStats(stats: StreamStats) {
        if (stats.state == ConnState.IDLE && stats.statusMessage.isEmpty()) return
        binding.tvStatus.text = stats.statusMessage.ifEmpty { stats.state.name }
        val color = when (stats.state) {
            ConnState.STREAMING, ConnState.CONNECTED -> "#2ECC71"
            ConnState.CONNECTING, ConnState.RECONNECTING, ConnState.SCANNING -> "#F5A623"
            ConnState.ERROR -> "#FF5C5C"
            else -> "#9AA4B2"
        }
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
        binding.levelMeter.progress = stats.level
        binding.tvLatency.text = if (stats.latencyMs >= 0) "${stats.latencyMs} ms" else "— ms"
        binding.tvBitrate.text = "${stats.bitrateKbps} kbps"

        val active = stats.state != ConnState.IDLE && stats.state != ConnState.ERROR
        binding.btnStream.text = if (active) getString(R.string.stop_stream) else getString(R.string.start_stream)
        binding.btnStream.setBackgroundResource(if (active) R.drawable.bg_button_danger else R.drawable.bg_button_primary)
    }
}
