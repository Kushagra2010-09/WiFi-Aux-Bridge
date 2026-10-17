package com.wifiauxbridge.audio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** FIFO of decoded PCM chunks between the network thread and the AudioTrack writer thread. */
class JitterBuffer(capacity: Int) {

    private val cap = capacity.coerceAtLeast(4)
    private val queue = ArrayBlockingQueue<ShortArray>(cap)

    fun offer(chunk: ShortArray): Boolean {
        // If full, drop the oldest chunk rather than letting latency build up.
        if (!queue.offer(chunk)) {
            queue.poll()
            return queue.offer(chunk)
        }
        return true
    }

    fun poll(timeoutMs: Long): ShortArray? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun size(): Int = queue.size

    fun fillPercent(): Int = ((queue.size * 100) / cap).coerceIn(0, 100)

    fun clear() = queue.clear()
}
