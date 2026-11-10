package com.music.spotui.video

import java.util.concurrent.CountDownLatch

/** Caller-owned synchronous loads; a cancelled owner never leaves a detached retry. */
internal class SingleFlightCache<K : Any, V : Any>(
    private val capacity: Int,
    private val ttlMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry<V>(val value: V, val at: Long)

    private class Flight<V> {
        private val latch = CountDownLatch(1)
        private var value: V? = null
        private var error: Throwable? = null

        fun complete(result: V) {
            value = result
            latch.countDown()
        }

        fun fail(t: Throwable) {
            error = t
            latch.countDown()
        }

        fun await(): V {
            latch.await()
            error?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return value as V
        }
    }

    private val lock = Any()
    private val values = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val flights = HashMap<K, Flight<V>>()

    init { require(capacity > 0 && ttlMs >= 0) }

    internal val cachedSize: Int get() = synchronized(lock) { values.size }
    internal val inFlightCount: Int get() = synchronized(lock) { flights.size }

    fun get(key: K, fresh: Boolean = false, load: () -> V): V {
        checkInterrupted()
        val flight: Flight<V>
        val owner: Boolean
        synchronized(lock) {
            val now = nowMs()
            values.entries.removeAll { (_, entry) -> now - entry.at !in 0 until ttlMs }
            val active = flights[key]
            if (active != null) {
                flight = active
                owner = false
            } else {
                if (!fresh) values[key]?.let { return it.value }
                // A forced refresh normally follows a refused URL; do not reuse it after failure.
                if (fresh) values.remove(key)
                flight = Flight()
                flights[key] = flight
                owner = true
            }
        }
        if (!owner) {
            try {
                return flight.await().also { checkInterrupted() }
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                throw t
            }
        }
        try {
            val value = load()
            checkInterrupted()
            synchronized(lock) {
                if (ttlMs > 0) values[key] = Entry(value, nowMs())
                while (values.size > capacity) values.remove(values.keys.first())
                flights.remove(key)
                flight.complete(value)
            }
            return value
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            synchronized(lock) {
                flights.remove(key)
                flight.fail(t)
            }
            throw t
        }
    }
}

internal fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Video metadata request interrupted")
}
