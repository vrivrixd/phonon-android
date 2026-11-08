package io.github.vrivrixd.phonon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class LeadBufferTest {
    private fun chunk(size: Int, value: Int = 0) = ByteArray(size) { value.toByte() }

    @Test(timeout = 5000) fun audioComesOutInOrderThenEnds() {
        val buffer = LeadBuffer(100)
        buffer.put(chunk(1, 1))
        buffer.put(chunk(1, 2))
        buffer.finish()
        assertArrayEquals(chunk(1, 1), buffer.take())
        assertArrayEquals(chunk(1, 2), buffer.take())
        assertNull(buffer.take())
    }

    @Test(timeout = 5000) fun aFullBufferMakesTheWriterWait() {
        val buffer = LeadBuffer(10)
        buffer.put(chunk(10))
        var second = false
        val writer = thread {
            buffer.put(chunk(1))
            second = true
        }
        Thread.sleep(200)
        assertFalse(second)
        buffer.take()
        writer.join()
        assertTrue(second)
    }

    @Test(timeout = 5000) fun cancelReleasesAWaitingWriterAndReader() {
        val full = LeadBuffer(1)
        full.put(chunk(1))
        var accepted = true
        val writer = thread { accepted = full.put(chunk(1)) }
        val empty = LeadBuffer(1)
        var taken: ByteArray? = chunk(1)
        val reader = thread { taken = empty.take() }
        Thread.sleep(100)
        full.cancel()
        empty.cancel()
        writer.join()
        reader.join()
        assertFalse(accepted)
        assertNull(taken)
    }

    @Test(timeout = 5000) fun awaitLeadWaitsForEnoughAudio() {
        val buffer = LeadBuffer(100)
        var ready = false
        val waiter = thread {
            buffer.awaitLead(10)
            ready = true
        }
        buffer.put(chunk(5))
        Thread.sleep(200)
        assertFalse(ready)
        buffer.put(chunk(5))
        waiter.join()
        assertTrue(ready)
    }

    @Test(timeout = 5000) fun awaitLeadGivesUpWhenAShortUtteranceEnds() {
        val buffer = LeadBuffer(100)
        buffer.put(chunk(5))
        buffer.finish()
        buffer.awaitLead(50)
    }
}
