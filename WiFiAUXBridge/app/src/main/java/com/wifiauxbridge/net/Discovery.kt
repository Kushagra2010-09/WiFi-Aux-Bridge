package com.wifiauxbridge.net

import com.wifiauxbridge.DiscoveredDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

object DiscoveryProtocol {
    const val DISCOVER_MSG = "WIFIAUXBRIDGE_DISCOVER"
    const val HERE_PREFIX = "WIFIAUXBRIDGE_HERE"
}

/** RECEIVER side: answers discovery broadcasts with our code and TCP port. */
class DiscoveryResponder(private val udpPort: Int, private val tcpPort: Int, private val pairingCode: String) {

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "discovery-responder", isDaemon = true) {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(udpPort))
                s.broadcast = true
                socket = s
                val buf = ByteArray(512)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(packet)
                    } catch (e: SocketException) {
                        break
                    }
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (msg == DiscoveryProtocol.DISCOVER_MSG) {
                        val reply = "${DiscoveryProtocol.HERE_PREFIX}:$pairingCode:$tcpPort".toByteArray()
                        runCatching { s.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        running = false
        socket?.close()
        worker?.interrupt()
    }
}

/** TRANSMITTER side: broadcasts a discovery request and reports each reply. */
class DiscoveryClient(private val udpPort: Int) {

    @Volatile private var running = false
    private var worker: Thread? = null

    fun scan(durationMs: Long, onFound: (DiscoveredDevice) -> Unit, onFinished: () -> Unit) {
        if (running) return
        running = true
        worker = thread(name = "discovery-client", isDaemon = true) {
            val seen = mutableSetOf<String>()
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 400
                    val msgBytes = DiscoveryProtocol.DISCOVER_MSG.toByteArray()
                    val broadcastAddr = runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()
                    val deadline = System.currentTimeMillis() + durationMs
                    while (running && System.currentTimeMillis() < deadline) {
                        if (broadcastAddr != null) {
                            runCatching {
                                socket.send(DatagramPacket(msgBytes, msgBytes.size, broadcastAddr, udpPort))
                            }
                        }
                        val buf = ByteArray(512)
                        val recvPacket = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(recvPacket)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val text = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                        val parts = text.split(":")
                        if (parts.size == 3 && parts[0] == DiscoveryProtocol.HERE_PREFIX) {
                            val code = parts[1]
                            val port = parts[2].toIntOrNull() ?: continue
                            val ip = recvPacket.address.hostAddress ?: continue
                            if (seen.add("$ip:$port")) {
                                onFound(DiscoveredDevice(ip, port, code))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                running = false
                onFinished()
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
    }
}
