package com.wifiauxbridge.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
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
import com.wifiauxbridge.net.Protocol
import com.wifiauxbridge.net.StreamSession
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Foreground service (type mediaProjection): AudioPlaybackCapture -> ADPCM encode -> TCP send.
 * The Activity obtains MediaProjection consent and passes the result here; this service
 * calls startForeground() BEFORE getMediaProjection(), as Android 14+ requires.
 */
class TransmitterService : Service(), StreamSession.Listener {

    inner class LocalBinder : Binder() {
        fun getService(): TransmitterService = this@TransmitterService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val _stats = MutableLiveData(StreamStats(state = ConnState.IDLE))
    val stats: LiveData<StreamStats> get() = _stats

    private lateinit var prefs: Prefs
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var capturing = false

    @Volatile private var session: StreamSession? = null
    private var socket: Socket? = null
    @Volatile private var connecting = false
    @Volatile private var wantConnection = false
    private var targetIp: String = ""
    private var targetPort: Int = Prefs.DEFAULT_TCP_PORT

    @Volatile private var encoder: Adpcm.Encoder? = null
    private var seq = 0
    @Volatile private var bytesSinceTick = 0L
    @Volatile private var lastLatencyMs = -1L
    @Volatile private var lastLevel = 0

    private var statsTicker: Thread? = null
    @Volatile private var tickerRunning = false
    private var silentTicks = 0
    @Volatile private var foregroundStarted = false
    @Volatile private var disconnectedSince = 0L
    @Volatile private var followPhoneVolume = true
    @Volatile private var manualVolumePct = 70
    private var volumeThread: Thread? = null
    @Volatile private var volumeRunning = false
    private var silenceWarned = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                targetIp = intent.getStringExtra(EXTRA_IP) ?: ""
                targetPort = intent.getIntExtra(EXTRA_PORT, Prefs.DEFAULT_TCP_PORT)
                startForegroundNotification("Starting…")
                if (resultCode == 0 || data == null) {
                    publishError("Screen-capture permission was not granted.")
                    stopSelf()
                } else {
                    beginCapture(resultCode, data)
                }
            }
            ACTION_STOP -> shutDown()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ capture setup

    private fun beginCapture(resultCode: Int, data: Intent) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            publishError("Microphone permission is required for audio capture.")
            stopSelf()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            publishError(getString(R.string.capture_not_supported))
            stopSelf()
            return
        }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            publishError("Could not start audio capture: ${e.message}")
            stopSelf()
            return
        }
        if (projection == null) {
            publishError("System denied the audio capture session.")
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                publishError("Audio capture permission was revoked by the system.")
                shutDown(keepMessage = true)
            }
        }, mainHandler)

        val quality = prefs.quality
        val channelMask = if (quality.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(quality.sampleRate)
            .setChannelMask(channelMask)
            .build()

        val record = try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                quality.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = if (minBuf > 0) minBuf * 3 else quality.sampleRate * 2
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            publishError(getString(R.string.app_not_capturable))
            stopSelf()
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            publishError(getString(R.string.app_not_capturable))
            record.release()
            stopSelf()
            return
        }

        audioRecord = record
        wantConnection = true
        connectAndStream(quality.sampleRate, quality.channels)
    }

    // ------------------------------------------------------------ networking

    private fun connectAndStream(sampleRate: Int, channels: Int) {
        if (connecting) return
        connecting = true
        publishState(ConnState.CONNECTING, "Connecting to $targetIp:$targetPort…")
        thread(name = "tx-connect", isDaemon = true) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(targetIp, targetPort), 5000)
                socket = s
                // Fresh codec state per connection, matching the receiver's fresh decoder.
                encoder = Adpcm.Encoder(channels)
                seq = 0
                val newSession = StreamSession(s, this)
                session = newSession
                newSession.start()
                newSession.sendHello(sampleRate, channels, Protocol.CODEC_ADPCM)
                connecting = false
                disconnectedSince = 0L
                publishState(ConnState.CONNECTED, "Connected to $targetIp")
                startCaptureLoop(channels)
                startStatsTicker()
                startVolumeSync()
            } catch (e: Exception) {
                connecting = false
                if (disconnectedSince == 0L) disconnectedSince = System.currentTimeMillis()
                publishState(ConnState.ERROR, "Could not connect: ${e.message}")
                maybeReconnect()
            }
        }
    }

    private fun maybeReconnect() {
        if (!wantConnection) return
        if (!prefs.autoReconnect) {
            publishState(ConnState.DISCONNECTED, "Disconnected")
            return
        }
        if (disconnectedSince != 0L && System.currentTimeMillis() - disconnectedSince >= GIVE_UP_MS) {
            giveUp()
            return
        }
        publishState(ConnState.RECONNECTING, "Reconnecting…")
        mainHandler.postDelayed({
            if (wantConnection) {
                val quality = prefs.quality
                connectAndStream(quality.sampleRate, quality.channels)
            }
        }, 2500)
    }

    // ------------------------------------------------------------ capture loop

    private fun startCaptureLoop(channels: Int) {
        val record = audioRecord ?: return
        capturing = true
        record.startRecording()
        captureThread = thread(name = "tx-capture", isDaemon = true) {
            val chunkSamples = (record.sampleRate * 0.02 * channels).toInt().coerceAtLeast(channels)
            val shortBuf = ShortArray(chunkSamples)
            while (capturing) {
                val n = record.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                lastLevel = computeLevel(shortBuf, n)
                val enc = encoder?.encode(shortBuf, n, channels) ?: continue
                val s = session
                if (s != null) {
                    s.sendAudio(seq++, n, enc)
                    bytesSinceTick += enc.size
                }
            }
        }
    }

    private fun computeLevel(buf: ShortArray, count: Int): Int {
        var sum = 0.0
        for (i in 0 until count) sum += abs(buf[i].toInt()).toDouble()
        val mean = sum / count
        return ((mean / 32767.0) * 100.0 * 6.0).toInt().coerceIn(0, 100)
    }

    private fun stopCaptureLoop() {
        capturing = false
        captureThread?.interrupt()
        captureThread = null
        runCatching { audioRecord?.stop() }
    }

    // ------------------------------------------------------------ stats

    private fun startStatsTicker() {
        if (tickerRunning) return
        tickerRunning = true
        silentTicks = 0
        silenceWarned = false
        statsTicker = thread(name = "tx-stats", isDaemon = true) {
            while (tickerRunning) {
                val kbps = ((bytesSinceTick * 8) / 500).toInt()
                bytesSinceTick = 0
                val latency = lastLatencyMs
                val level = lastLevel
                // If we're connected and capturing but every sample is silent for ~4 s,
                // tell the user: the source app is probably blocking audio capture.
                if (capturing && session != null && level == 0) silentTicks++ else silentTicks = 0
                if (silentTicks >= 8 && !silenceWarned) {
                    silenceWarned = true
                    publishState(
                        ConnState.STREAMING,
                        "Connected, but NO audio is being captured. Play music in Chrome, YouTube or another app - some apps and DRM content block capture."
                    )
                } else if (silentTicks == 0 && silenceWarned) {
                    silenceWarned = false
                    publishState(ConnState.STREAMING, "Streaming to $targetIp")
                }
                mainHandler.post {
                    val current = _stats.value ?: StreamStats()
                    _stats.value = current.copy(
                        bitrateKbps = kbps,
                        latencyMs = latency,
                        level = level,
                        peerAddress = targetIp
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

    // ------------------------------------------------------------ remote volume

    /**
     * The receiver's volume follows either this phone's media volume (follow = true) or a
     * fixed value chosen in the app (follow = false), so this phone itself can be kept quiet.
     */
    fun setVolumeControl(follow: Boolean, manualPct: Int) {
        followPhoneVolume = follow
        manualVolumePct = manualPct.coerceIn(0, 100)
    }

    private fun startVolumeSync() {
        if (volumeRunning) return
        volumeRunning = true
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        volumeThread = thread(name = "tx-volume", isDaemon = true) {
            var lastSession: StreamSession? = null
            var lastSent = -1
            while (volumeRunning) {
                val sess = session
                if (sess != null) {
                    val permille = if (followPhoneVolume) {
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        am.getStreamVolume(AudioManager.STREAM_MUSIC) * 1000 / max
                    } else {
                        manualVolumePct * 10
                    }
                    if (sess !== lastSession || permille != lastSent) {
                        sess.sendVolume(permille)
                        lastSession = sess
                        lastSent = permille
                    }
                }
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopVolumeSync() {
        volumeRunning = false
        volumeThread?.interrupt()
        volumeThread = null
    }

    // ------------------------------------------------------------ StreamSession.Listener

    override fun onHelloAck() {
        publishState(ConnState.STREAMING, "Streaming to $targetIp")
    }

    override fun onLatencyUpdate(ms: Long) {
        lastLatencyMs = ms
    }

    override fun onBye() {
        publishState(ConnState.DISCONNECTED, "Receiver disconnected")
    }

    override fun onClosed(error: Throwable?) {
        if (!wantConnection) return
        stopCaptureLoop()
        stopStatsTicker()
        session = null
        runCatching { socket?.close() }
        val msg = if (error != null) "Connection lost: ${error.message}" else "Disconnected"
        if (disconnectedSince == 0L) disconnectedSince = System.currentTimeMillis()
        publishState(ConnState.DISCONNECTED, msg)
        maybeReconnect()
    }

    // ------------------------------------------------------------ lifecycle

    fun requestStop() = shutDown()

    private fun shutDown(finalMessage: String = "Stopped", keepMessage: Boolean = false) {
        stopEverything(publish = !keepMessage)
        if (!keepMessage) {
            mainHandler.post {
                _stats.value = (_stats.value ?: StreamStats()).copy(state = ConnState.IDLE, statusMessage = finalMessage)
            }
        }
        foregroundStarted = false
        stopSelf()
    }

    /** Receiver unreachable for 10 minutes: stop retrying so the battery isn't drained. */
    private fun giveUp() {
        notifyInfo("Stopped: couldn't reach the receiver for 10 minutes.")
        shutDown("Stopped: receiver not found for 10 minutes")
    }

    private fun stopEverything(publish: Boolean = true) {
        wantConnection = false
        stopVolumeSync()
        stopCaptureLoop()
        stopStatsTicker()
        session?.let { it.sendBye(); it.close() }
        session = null
        runCatching { socket?.close() }
        socket = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
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

    private fun publishError(message: String) = publishState(ConnState.ERROR, message)

    private fun startForegroundNotification(text: String) {
        foregroundStarted = true
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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
            .setContentTitle("Wi-Fi AUX Bridge — Transmitter")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

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
        private const val GIVE_UP_MS = 10 * 60 * 1000L
        private const val NOTIF_ID_INFO = 504
        const val ACTION_START = "com.wifiauxbridge.action.START_TX"
        const val ACTION_STOP = "com.wifiauxbridge.action.STOP_TX"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        private const val NOTIF_ID = 501
    }
}
