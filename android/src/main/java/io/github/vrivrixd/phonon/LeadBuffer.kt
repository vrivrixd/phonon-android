package io.github.vrivrixd.phonon

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Audio made but not yet handed to Android. Android accepts only about half a second ahead of what
 * is playing, then blocks whoever is writing. Writing straight from the model would hold it to that
 * half second, so any slow moment longer than that is heard as a gap. With this in between the model
 * keeps working ahead whenever it can, and that lead covers the slow moments.
 */
class LeadBuffer(private val capacityBytes: Int) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var buffered = 0
    private var finished = false
    private var canceled = false

    /** Add audio, waiting while the buffer is full. False once canceled. */
    fun put(chunk: ByteArray): Boolean = lock.withLock {
        while (buffered >= capacityBytes && !canceled) changed.await()
        if (canceled) return false
        chunks.addLast(chunk)
        buffered += chunk.size
        changed.signalAll()
        true
    }

    /** No more audio is coming. */
    fun finish() = lock.withLock {
        finished = true
        changed.signalAll()
    }

    /** Drop everything and release anyone waiting. */
    fun cancel() = lock.withLock {
        canceled = true
        chunks.clear()
        buffered = 0
        changed.signalAll()
    }

    /** The next piece of audio, waiting for one; null when finished or canceled. */
    fun take(): ByteArray? = lock.withLock {
        while (chunks.isEmpty() && !finished && !canceled) changed.await()
        val chunk = chunks.removeFirstOrNull() ?: return null
        buffered -= chunk.size
        changed.signalAll()
        chunk
    }

    /** Wait until [bytes] are ready, or no more are coming. */
    fun awaitLead(bytes: Int) = lock.withLock {
        while (buffered < bytes && !finished && !canceled) changed.await()
    }
}
