package com.wifiauxbridge.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.wifiauxbridge.ConnState
import com.wifiauxbridge.MainActivity
import com.wifiauxbridge.Prefs
import com.wifiauxbridge.R
import com.wifiauxbridge.StreamStats
import com.wifiauxbridge.WiFiAuxApp
import com.wifiauxbridge.audio.Adpcm
import com.wifiauxbridge.audio.JitterBuffer
import com.wifiauxbridge.net.DiscoveryResponder
import com.wifiauxbridge.net.StreamSession
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Foreground service (type mediaPlayback): accepts one transmitter, decodes ADPCM audio,
 * and plays it through the normal audio output (headphone/AUX) via AudioTrack.
 */
class ReceiverService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ReceiverService = this@ReceiverService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val _stats = MutableLiveData(StreamStats(state = ConnState.IDLE))
    val stats: LiveData<StreamStats> get() = _stats

    private lateinit var prefs: Prefs
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Stays the same across restarts so the transmitter can recognise this receiver later. */
    lateinit var pairingCode: String
        private set

    private lateinit var audioManager: AudioManager
    @Volatile private var watching = false
    @Volatile private var manualStart = false
    @Volatile private var foregroundStarted = false
    private var deviceCallback: AudioDeviceCallback? = null
    private val idleRunnable = Runnable { onIdleTimeout() }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false

    @Volatile private var session: StreamSession? = null
    @Volatile private var clientSocket: Socket? = null
    private var discoveryResponder: DiscoveryResponder? = null

    @Volatile private var decoder: Adpcm.Decoder? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var jitterBuffer: JitterBuffer? = null
    private var playbackThread: Thread? = null
    @Volatile private var playing = false
    @Volatile private var streamChannels = 1
    @Volatile private var gain = 1f

    @Volatile private var bytesSinceTick = 0L
    @Volatile private var lastLatencyMs = -1L
    private var statsTicker: Thread? = null
    @Volatile private var tickerRunning = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val sp = getSharedPreferences("rx_state", MODE_PRIVATE)
        val saved = sp.getString("code", null)
        pairingCode = if (saved != null && saved.length == 4) {
            saved
        } else {
            val fresh = (1000..9999).random().toString()
            sp.edit().putString("code", fresh).apply()
            fresh
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!foregroundStarted) startForegroundNotification("Waiting for connection…")
                manualStart = true
                startServer()
            }
            ACTION_WATCH -> {
                if (!foregroundStarted) startForegroundNotification("Waiting for AUX cable…")
                startWatching()
            }
            ACTION_STOP -> shutDown()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ server

    private fun startServer() {
        if (running) return
        running = true
        val port = prefs.tcpPort
        acceptThread = thread(name = "rx-accept", isDaemon = true) {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                serverSocket = server
                discoveryResponder =
                    DiscoveryResponder(Prefs.UDP_DISCOVERY_PORT, port, pairingCode).apply { start() }
                publishState(ConnState.DISCONNECTED, "Waiting for a transmitter to connect…")
                armIdleTimer()
                while (running) {
                    val incoming = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (running) publishState(ConnState.ERROR, "Server error: ${e.message}")
                        break
                    }
                    // A new connection always replaces an old one (the old one may be a dead
                    // half-open socket after a Wi-Fi drop), so reconnecting always works.
                    if (session != null) cleanupSession()
                    cancelIdleTimer()
                    incoming.tcpNoDelay = true
                    clientSocket = incoming
                    val listener = RxListener()
                    val newSession = StreamSession(incoming, listener)
                    listener.owner = newSession
                    session = newSession
                    newSession.start()
                    publishState(
                        ConnState.CONNECTED,
                        "Connected to ${incoming.inetAddress?.hostAddress ?: "transmitter"}"
                    )
                    startStatsTicker()
                }
            } catch (e: Exception) {
                if (running) publishState(ConnState.ERROR, "Could not open port $port: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ per-connection listener

    /** Ignores callbacks from sessions that have been replaced or closed. */
    private inner class RxListener : StreamSession.Listener {
        @Volatile var owner: StreamSession? = null
        private fun mine(): Boolean = owner != null && owner === session

        override fun onHello(sampleRate: Int, channels: Int, codec: Int) {
            if (mine()) handleHello(sampleRate, channels)
        }

        override fun onAudio(seq: Int, sampleCount: Int, data: ByteArray) {
            if (mine()) handleAudio(sampleCount, data)
        }

        override fun onLatencyUpdate(ms: Long) {
            if (mine()) lastLatencyMs = ms
        }

        override fun onVolume(permille: Int) {
            if (mine()) handleVolume(permille)
        }

        override fun onBye() {
            if (mine()) publishState(ConnState.DISCONNECTED, "Transmitter disconnected")
        }

        override fun onClosed(error: Throwable?) {
            if (mine()) handleClosed(error)
        }
    }

    /** Volume chosen on the transmitter. Squared so the slider feels natural (roughly dB-like). */
    private fun handleVolume(permille: Int) {
        val f = permille.coerceIn(0, 1000) / 1000f
        gain = f * f
        runCatching { audioTrack?.setVolume(gain) }
    }

    private fun handleHello(sampleRate: Int, channels: Int) {
        gain = 1f // the transmitter sends its volume right after HELLO
        try {
            setupPlayback(sampleRate, channels)
        } catch (e: Exception) {
            publishState(ConnState.ERROR, "Audio output error: ${e.message}")
            return
        }
        session?.sendHelloAck()
        publishState(ConnState.STREAMING, "Streaming")
    }

    private fun handleAudio(sampleCount: Int, data: ByteArray) {
        bytesSinceTick += data.size
        val dec = decoder ?: return
        if (sampleCount <= 0) return
        val pcm = dec.decode(data, data.size, sampleCount, streamChannels)
        jitterBuffer?.offer(pcm)
    }

    private fun handleClosed(error: Throwable?) {
        if (!running) return
        val msg = if (error != null) "Connection ended" else "Disconnected"
        cleanupSession()
        publishState(ConnState.DISCONNECTED, "$msg — waiting for a transmitter…")
        armIdleTimer()
    }

    private fun cleanupSession() {
        stopPlayback()
        stopStatsTicker()
        session = null
        runCatching { clientSocket?.close() }
        clientSocket = null
    }

    // ------------------------------------------------------------ playback

    private fun setupPlayback(sampleRate: Int, channels: Int) {
        stopPlayback()
        streamChannels = channels
        decoder = Adpcm.Decoder(channels)

        val channelOutMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelOutMask, AudioFormat.ENCODING_PCM_16BIT)
        val trackBufferBytes = (minBuf * 2).coerceAtLeast(4096)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelOutMask)
            .build()

        val builder = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(trackBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && prefs.lowLatency) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = builder.build()
        track.setVolume(gain)
        audioTrack = track

        val chunkMs = 20
        val chunkCapacity = (prefs.effectiveBufferMs() / chunkMs).coerceAtLeast(3)
        val jb = JitterBuffer(chunkCapacity * 2)
        jitterBuffer = jb

        track.play()
        playing = true
        playbackThread = thread(name = "rx-playback", isDaemon = true) {
            try {
                // Prime the buffer briefly to absorb initial network jitter.
                val primeDeadline = System.currentTimeMillis() + 400
                while (playing && jb.size() < chunkCapacity && System.currentTimeMillis() < primeDeadline) {
                    Thread.sleep(10)
                }
                while (playing) {
                    val chunk = jb.poll(200) ?: continue
                    track.write(chunk, 0, chunk.size)
                }
            } catch (_: InterruptedException) {
            } catch (_: IllegalStateException) {
            }
        }
    }

    private fun stopPlayback() {
        playing = false
        playbackThread?.interrupt()
        playbackThread = null
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        jitterBuffer?.clear()
        jitterBuffer = null
        decoder = null
    }

    // ------------------------------------------------------------ stats

    private fun startStatsTicker() {
        if (tickerRunning) return
        tickerRunning = true
        statsTicker = thread(name = "rx-stats", isDaemon = true) {
            while (tickerRunning) {
                val kbps = ((bytesSinceTick * 8) / 500).toInt()
                bytesSinceTick = 0
                val bufferPct = jitterBuffer?.fillPercent() ?: 0
                val latency = lastLatencyMs
                val peer = clientSocket?.inetAddress?.hostAddress ?: ""
                mainHandler.post {
                    val current = _stats.value ?: StreamStats()
                    _stats.value = current.copy(
                        bitrateKbps = kbps,
                        latencyMs = latency,
                        bufferPercent = bufferPct,
                        peerAddress = peer
                    )
                }
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopStatsTicker() {
        tickerRunning = false
        statsTicker?.interrupt()
        statsTicker = null
    }

    // ------------------------------------------------------------ AUX cable watching

    /** Called by the UI when "auto-start on AUX" is switched off. */
    fun disableAutoWatch() {
        stopWatching()
        if (!manualStart) shutDown()
    }

    private fun startWatching() {
        if (watching) {
            evaluateAux()
            return
        }
        watching = true
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = evaluateAux()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = evaluateAux()
        }
        deviceCallback = cb
        audioManager.registerAudioDeviceCallback(cb, mainHandler)
        evaluateAux()
    }

    private fun stopWatching() {
        deviceCallback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
        watching = false
    }

    private fun auxPlugged(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { d ->
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && d.type == AudioDeviceInfo.TYPE_USB_HEADSET)
        }
    }

    /** Starts listening when a cable is plugged in, and pauses when it is removed. */
    private fun evaluateAux() {
        if (!watching) return
        if (auxPlugged()) {
            if (!running) {
                manualStart = false
                startServer()
            }
        } else if (!manualStart) {
            if (running) stopServerOnly()
            publishState(ConnState.DISCONNECTED, "Waiting for AUX cable…")
        }
    }

    // ------------------------------------------------------------ 10-minute idle timeout

    private fun armIdleTimer() {
        mainHandler.removeCallbacks(idleRunnable)
        mainHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
    }

    private fun cancelIdleTimer() {
        mainHandler.removeCallbacks(idleRunnable)
    }

    /** Nobody connected for 10 minutes: stop searching so the battery isn't drained. */
    private fun onIdleTimeout() {
        if (session != null || !running) return
        val reason = "No transmitter found for 10 minutes."
        if (watching) {
            manualStart = false
            stopServerOnly()
            publishState(ConnState.DISCONNECTED, "$reason Waiting for AUX cable - re-plug it to search again.")
            notifyInfo("$reason Receiver paused. Re-plug the AUX cable to search again.")
        } else {
            notifyInfo("Receiver stopped: $reason")
            shutDown("Stopped: $reason")
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun requestStop() = shutDown()

    private fun shutDown(finalMessage: String = "Stopped") {
        stopEverything()
        mainHandler.post {
            _stats.value = (_stats.value ?: StreamStats()).copy(state = ConnState.IDLE, statusMessage = finalMessage)
        }
        foregroundStarted = false
        stopSelf()
    }

    /** Closes the listener, any session and audio, but keeps the service (and cable watcher) alive. */
    private fun stopServerOnly() {
        running = false
        cancelIdleTimer()
        stopPlayback()
        stopStatsTicker()
        discoveryResponder?.stop()
        discoveryResponder = null
        session?.let { it.sendBye(); it.close() }
        session = null
        runCatching { clientSocket?.close() }
        clientSocket = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun stopEverything(publish: Boolean = true) {
        stopWatching()
        manualStart = false
        stopServerOnly()
        if (publish) publishState(ConnState.IDLE, "Stopped")
    }

    override fun onDestroy() {
        stopEverything(publish = false)
        foregroundStarted = false
        super.onDestroy()
    }

    // ------------------------------------------------------------ state / notification

    private fun publishState(state: ConnState, message: String) {
        mainHandler.post {
            _stats.value = (_stats.value ?: StreamStats()).copy(state = state, statusMessage = message)
        }
        updateNotification(message)
    }

    private fun startForegroundNotification(text: String) {
        foregroundStarted = true
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (!foregroundStarted) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, WiFiAuxApp.CHANNEL_ID)
            .setContentTitle("Wi-Fi AUX Bridge — Receiver")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    /** A normal (dismissible) notification, used to tell the user why we stopped. */
    private fun notifyInfo(text: String) {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, WiFiAuxApp.CHANNEL_ID)
            .setContentTitle("Wi-Fi AUX Bridge")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID_INFO, n)
    }

    companion object {
        const val ACTION_START = "com.wifiauxbridge.action.START_RX"
        const val ACTION_WATCH = "com.wifiauxbridge.action.WATCH_AUX"
        const val ACTION_STOP = "com.wifiauxbridge.action.STOP_RX"
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val NOTIF_ID_INFO = 503
        private const val NOTIF_ID = 502
    }
}
