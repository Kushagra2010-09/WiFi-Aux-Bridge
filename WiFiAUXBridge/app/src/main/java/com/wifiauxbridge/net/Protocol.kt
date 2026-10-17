package com.wifiauxbridge.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.nio.ByteBuffer

/** Frame layout: [1 byte type][4 byte big-endian payload length][payload bytes] */
object Protocol {
    const val TYPE_HELLO: Int = 1
    const val TYPE_HELLO_ACK: Int = 2
    const val TYPE_AUDIO: Int = 3
    const val TYPE_PING: Int = 4
    const val TYPE_PONG: Int = 5
    const val TYPE_BYE: Int = 6

    const val CODEC_ADPCM: Int = 1
    const val MAX_PAYLOAD_BYTES: Int = 64 * 1024
}

class Packet(val type: Int, val payload: ByteArray)

class PacketIO(private val socket: Socket) {

    private val input = DataInputStream(socket.getInputStream().buffered(32 * 1024))
    private val output = DataOutputStream(socket.getOutputStream().buffered(32 * 1024))
    private val writeLock = Any()

    fun send(type: Int, payload: ByteArray = ByteArray(0)) {
        synchronized(writeLock) {
            output.writeByte(type)
            output.writeInt(payload.size)
            if (payload.isNotEmpty()) output.write(payload)
            output.flush()
        }
    }

    @Throws(EOFException::class)
    fun readOne(): Packet {
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (length < 0 || length > Protocol.MAX_PAYLOAD_BYTES) {
            throw EOFException("Invalid frame length $length")
        }
        val payload = ByteArray(length)
        if (length > 0) input.readFully(payload)
        return Packet(type, payload)
    }

    fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }
}

object PayloadCodec {

    fun encodeHello(sampleRate: Int, channels: Int, codec: Int): ByteArray {
        val b = ByteBuffer.allocate(6)
        b.putInt(sampleRate)
        b.put(channels.toByte())
        b.put(codec.toByte())
        return b.array()
    }

    class Hello(val sampleRate: Int, val channels: Int, val codec: Int)

    fun decodeHello(payload: ByteArray): Hello {
        val b = ByteBuffer.wrap(payload)
        val sr = b.int
        val ch = b.get().toInt()
        val codec = b.get().toInt()
        return Hello(sr, ch, codec)
    }

    fun encodeTimestamp(ts: Long): ByteArray = ByteBuffer.allocate(8).putLong(ts).array()

    fun decodeTimestamp(payload: ByteArray): Long = ByteBuffer.wrap(payload).long

    /** [sampleCount] = total interleaved samples that [data] decodes to. */
    fun encodeAudio(seq: Int, sampleCount: Int, data: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + data.size)
        b.putInt(seq)
        b.putInt(sampleCount)
        b.put(data)
        return b.array()
    }

    class Audio(val seq: Int, val sampleCount: Int, val data: ByteArray)

    fun decodeAudio(payload: ByteArray): Audio {
        val b = ByteBuffer.wrap(payload)
        val seq = b.int
        val sampleCount = b.int
        val data = ByteArray(payload.size - 8)
        b.get(data)
        return Audio(seq, sampleCount, data)
    }
}
