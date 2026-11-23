package com.extbridge.helper

import java.util.concurrent.Executor

/** Coalesce MOVE events, but serialize every button/scroll event on the same FIFO executor. */
internal class MouseInputQueue(
    private val executor: Executor,
    private val send: (Int, Float, Float) -> IntArray?,
    private val result: (Int, IntArray?) -> Unit,
) {
    private var dx = 0f
    private var dy = 0f
    private var draining = false
    private var accepting = true

    @Synchronized
    fun offer(type: Int, x: Float = 0f, y: Float = 0f) {
        if (!accepting) return
        if (type != 0) {
            executor.execute { result(type, runCatching { send(type, x, y) }.getOrNull()) }
            return
        }
        dx += x; dy += y
        if (draining) return
        draining = true
        executor.execute {
            while (true) {
                val move = synchronized(this) {
                    if (dx == 0f && dy == 0f) {
                        draining = false
                        null
                    } else {
                        (dx to dy).also { dx = 0f; dy = 0f }
                    }
                } ?: break
                result(0, runCatching { send(0, move.first, move.second) }.getOrNull())
            }
        }
    }

    @Synchronized
    fun clearMovement() { dx = 0f; dy = 0f }

    /** Call after enqueueing LEFT_UP; queued events are allowed to finish during Executor.shutdown(). */
    @Synchronized
    fun close() { accepting = false; dx = 0f; dy = 0f }
}
