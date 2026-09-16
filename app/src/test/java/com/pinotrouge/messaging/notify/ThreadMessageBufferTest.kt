package com.pinotrouge.messaging.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadMessageBufferTest {

    @Test
    fun `append keeps messages for the same thread`() {
        val buffer = ThreadMessageBuffer()
        buffer.append(1L, entry("a", 1))
        val list = buffer.append(1L, entry("b", 2))
        assertEquals(2, list.size)
        assertEquals("a", list[0].body)
        assertEquals("b", list[1].body)
    }

    @Test
    fun `append caps at max messages`() {
        val buffer = ThreadMessageBuffer(maxMessages = 3)
        repeat(5) { i ->
            buffer.append(9L, entry("m$i", i.toLong()))
        }
        val snap = buffer.snapshot(9L)
        assertEquals(3, snap.size)
        assertEquals("m2", snap[0].body)
        assertEquals("m4", snap[2].body)
    }

    @Test
    fun `threads are independent`() {
        val buffer = ThreadMessageBuffer()
        buffer.append(1L, entry("t1", 1))
        buffer.append(2L, entry("t2", 1))
        assertEquals(1, buffer.snapshot(1L).size)
        assertEquals("t2", buffer.snapshot(2L).single().body)
    }

    @Test
    fun `clear drops thread history`() {
        val buffer = ThreadMessageBuffer()
        buffer.append(5L, entry("x", 1))
        buffer.clear(5L)
        assertTrue(buffer.snapshot(5L).isEmpty())
    }

    private fun entry(body: String, ts: Long) = ThreadMessageBuffer.Entry(
        body = body,
        timestampMillis = ts,
        senderDisplayName = "Sender",
    )
}
