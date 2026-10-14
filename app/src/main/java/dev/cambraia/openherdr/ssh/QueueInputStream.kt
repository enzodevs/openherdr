package dev.cambraia.openherdr.ssh

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * Hands bytes written on one thread to a reader on another (JSch's channel thread).
 * Unlike [java.io.PipedInputStream] it does not fail when the writing thread ends,
 * which matters because coroutine writes come from a shared thread pool.
 */
class QueueInputStream : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray = ByteArray(0)
    private var offset = 0

    @Volatile
    private var closed = false

    val sink: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "stream closed" }
            if (len > 0) chunks.put(b.copyOfRange(off, off + len))
        }

        override fun close() = this@QueueInputStream.close()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (offset >= current.size) {
            val next = chunks.take()
            if (next === EOF) {
                chunks.put(EOF)
                return -1
            }
            current = next
            offset = 0
        }
        val count = minOf(len, current.size - offset)
        System.arraycopy(current, offset, b, off, count)
        offset += count
        return count
    }

    override fun close() {
        if (!closed) {
            closed = true
            chunks.put(EOF)
        }
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}
