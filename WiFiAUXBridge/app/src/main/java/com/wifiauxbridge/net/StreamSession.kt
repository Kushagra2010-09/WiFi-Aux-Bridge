package com.wifiauxbridge.net

import java.net.Socket
import kotlin.concurrent.thread

/** Read loop + ping loop for one connected socket; used identically by both roles. */
class StreamSession(
    socket: Socket,
    private val listener: Listener
) {
    interface Listener {
        fun onHello(sampleRate: Int, channels: Int, codec: Int) {}
        fun onHelloAck() {}
        fun onAudio(seq: Int, sampleCount: Int, data: ByteArray) {}
        fun onLatencyUpdate(ms: Long) {}
        fun onBye() {}
        fun onVolume(permille: Int) {}
        fun onClosed(error: Throwable?) {}
    }

    private val io = PacketIO(socket)
    @Volatile private var running = true
    private var readThread: Thread? = null
    private var pingThread: Thread? = null

    fun start() {
        readThread = thread(name = "session-read", isDaemon = true) { readLoop() }
        pingThread = thread(name = "session-ping", isDaemon = true) { pingLoop() }
    }

    private fun readLoop() {
        var error: Throwable? = null
        try {
            while (running) {
                val pkt = io.readOne()
                when (pkt.type) {
                    Protocol.TYPE_HELLO -> {
                        val h = PayloadCodec.decodeHello(pkt.payload)
                        listener.onHello(h.sampleRate, h.channels, h.codec)
                    }
                    Protocol.TYPE_HELLO_ACK -> listener.onHelloAck()
                    Protocol.TYPE_AUDIO -> {
                        val a = PayloadCodec.decodeAudio(pkt.payload)
                        listener.onAudio(a.seq, a.sampleCount, a.data)
                    }
                    Protocol.TYPE_PING -> {
                        runCatching { io.send(Protocol.TYPE_PONG, pkt.payload) }
                    }
                    Protocol.TYPE_PONG -> {
                        val sentAtNanos = PayloadCodec.decodeTimestamp(pkt.payload)
                        val rttNanos = System.nanoTime() - sentAtNanos
                        val latencyMs = (rttNanos / 1_000_000L) / 2L
                        listener.onLatencyUpdate(latencyMs.coerceAtLeast(0))
                    }
                    Protocol.TYPE_VOLUME -> {
                        if (pkt.payload.size >= 2) listener.onVolume(PayloadCodec.decodeVolume(pkt.payload))
                    }
                    Protocol.TYPE_BYE -> {
                        listener.onBye()
                        running = false
                    }
                }
            }
        } catch (e: Exception) {
            error = e
        } finally {
            val wasRunning = running
            running = false
            listener.onClosed(if (wasRunning) error else null)
        }
    }

    private fun pingLoop() {
        try {
            while (running) {
                runCatching { io.send(Protocol.TYPE_PING, PayloadCodec.encodeTimestamp(System.nanoTime())) }
                Thread.sleep(1000)
            }
        } catch (_: InterruptedException) {
        }
    }

    fun sendHello(sampleRate: Int, channels: Int, codec: Int) =
        runCatching { io.send(Protocol.TYPE_HELLO, PayloadCodec.encodeHello(sampleRate, channels, codec)) }

    fun sendHelloAck() = runCatching { io.send(Protocol.TYPE_HELLO_ACK) }

    fun sendAudio(seq: Int, sampleCount: Int, data: ByteArray) =
        runCatching { io.send(Protocol.TYPE_AUDIO, PayloadCodec.encodeAudio(seq, sampleCount, data)) }

    fun sendVolume(permille: Int) =
        runCatching { io.send(Protocol.TYPE_VOLUME, PayloadCodec.encodeVolume(permille)) }

    fun sendBye() = runCatching { io.send(Protocol.TYPE_BYE) }

    fun close() {
        running = false
        io.close()
        readThread?.interrupt()
        pingThread?.interrupt()
    }
}
