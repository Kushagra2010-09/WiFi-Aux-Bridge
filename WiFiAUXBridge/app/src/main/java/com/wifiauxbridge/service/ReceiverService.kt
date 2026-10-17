package com.wifiauxbridge.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
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
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Foreground service (type mediaPlayback): accepts one transmitter, decodes ADPCM audio,
 * and plays it through the normal audio output (headphone/AUX) via AudioTrack.
 */
class ReceiverService : Service(), StreamSession.Listener {

    inner class LocalBinder : Binder() {
        fun getService(): ReceiverService = this@ReceiverService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val _stats = MutableLiveData(StreamStats(state = ConnState.IDLE))
    val stats: LiveData<StreamStats> get() = _stats

    private lateinit var prefs: Prefs
    private val mainHandler = Handler(Looper.getMainLooper())

    val pairingCode: String = (1000..9999).random().toString()

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

    @Volatile private var bytesSinceTick = 0L
    @Volatile private var lastLatencyMs = -1L
    private var statsTicker: Thread? = null
    @Volatile private var tickerRunning = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification("Waiting for connection…")
                startServer()
            }
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
            }
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
                val server = ServerSocket(port)
                serverSocket = server
                discoveryResponder =
                    DiscoveryResponder(Prefs.UDP_DISCOVERY_PORT, port, pairingCode).apply { start() }
                publishState(ConnState.DISCONNECTED, "Waiting for a transmitter to connect…")
                while (running) {
                    val incoming = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (running) publishState(ConnState.ERROR, "Server error: ${e.message}")
                        break
                    }
                    if (session != null) {
                        runCatching { incoming.close() }
                        continue
                    }
                    incoming.tcpNoDelay = true
                    clientSocket = incoming
                    val newSession = StreamSession(incoming, this)
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

    // ------------------------------------------------------------ StreamSession.Listener

    override fun onHello(sampleRate: Int, channels: Int, codec: Int) {
        try {
            setupPlayback(sampleRate, channels)
        } catch (e: Exception) {
            publishState(ConnState.ERROR, "Audio output error: ${e.message}")
            return
        }
        session?.sendHelloAck()
        publishState(ConnState.STREAMING, "Streaming")
    }

    override fun onAudio(seq: Int, sampleCount: Int, data: ByteArray) {
        bytesSinceTick += data.size
        val dec = decoder ?: return
        if (sampleCount <= 0) return
        val pcm = dec.decode(data, data.size, sampleCount, streamChannels)
        jitterBuffer?.offer(pcm)
    }

    override fun onLatencyUpdate(ms: Long) {
        lastLatencyMs = ms
    }

    override fun onBye() {
        publishState(ConnState.DISCONNECTED, "Transmitter disconnected")
    }

    override fun onClosed(error: Throwable?) {
        if (!running) return
        val msg = if (error != null) "Connection lost: ${error.message}" else "Disconnected"
        cleanupSession()
        publishState(ConnState.DISCONNECTED, "$msg — waiting for a transmitter…")
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

    // ------------------------------------------------------------ lifecycle

    fun requestStop() {
        stopEverything()
        stopSelf()
    }

    private fun stopEverything() {
        running = false
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
        publishState(ConnState.IDLE, "Stopped")
    }

    override fun onDestroy() {
        stopEverything()
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
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
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

    companion object {
        const val ACTION_START = "com.wifiauxbridge.action.START_RX"
        const val ACTION_STOP = "com.wifiauxbridge.action.STOP_RX"
        private const val NOTIF_ID = 502
    }
}
