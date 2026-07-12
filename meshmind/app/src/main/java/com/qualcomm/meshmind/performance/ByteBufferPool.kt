package com.qualcomm.meshmind.performance

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Recycler class for pre-allocated byte array buffers to minimize GC frequency under packet throughput.
 */
class ByteBufferPool {

    private val pool512 = ConcurrentLinkedQueue<ByteArray>()
    private val pool1024 = ConcurrentLinkedQueue<ByteArray>()
    private val pool4096 = ConcurrentLinkedQueue<ByteArray>()

    companion object {
        private const val SIZE_512 = 512
        private const val SIZE_1024 = 1024
        private const val SIZE_4096 = 4096

        @Volatile
        private var instance: ByteBufferPool? = null

        fun getInstance(): ByteBufferPool {
            return instance ?: synchronized(this) {
                instance ?: ByteBufferPool().also { instance = it }
            }
        }
    }

    /**
     * Acquires a recycled byte array of at least the requested size.
     */
    fun acquire(minSize: Int): ByteArray {
        return when {
            minSize <= SIZE_512 -> pool512.poll() ?: ByteArray(SIZE_512)
            minSize <= SIZE_1024 -> pool1024.poll() ?: ByteArray(SIZE_1024)
            minSize <= SIZE_4096 -> pool4096.poll() ?: ByteArray(SIZE_4096)
            else -> ByteArray(minSize)
        }
    }

    /**
     * Releases a byte array back to the matching size pool.
     */
    fun release(buffer: ByteArray) {
        val size = buffer.size
        // Clear elements before recycling to prevent memory leak leaks
        buffer.fill(0)
        
        when (size) {
            SIZE_512 -> pool512.offer(buffer)
            SIZE_1024 -> pool1024.offer(buffer)
            SIZE_4096 -> pool4096.offer(buffer)
        }
    }

    fun clear() {
        pool512.clear()
        pool1024.clear()
        pool4096.clear()
    }
}
