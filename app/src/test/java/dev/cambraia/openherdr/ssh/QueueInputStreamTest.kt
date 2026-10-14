package dev.cambraia.openherdr.ssh

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.concurrent.thread

class QueueInputStreamTest {
    @Test
    fun `bytes written by a thread that has exited are still readable, then EOF`() {
        val queue = QueueInputStream()
        thread { queue.sink.write("hello\n".toByteArray()) }.join()
        thread { queue.sink.write("world".toByteArray()); queue.sink.close() }.join()
        assertEquals("hello\nworld", queue.readBytes().decodeToString())
        assertEquals(-1, queue.read())
    }

    @Test
    fun `read blocks until data arrives`() {
        val queue = QueueInputStream()
        val writer = thread { Thread.sleep(100); queue.sink.write(byteArrayOf(42)) }
        assertEquals(42, queue.read())
        writer.join()
    }
}
