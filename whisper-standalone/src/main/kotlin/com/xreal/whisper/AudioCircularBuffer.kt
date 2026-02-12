package com.xreal.whisper

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A thread-safe circular buffer for ShortArray audio data.
 * 
 * Performance:
 * - Constant time O(1) write.
 * - Optimized for VAD-triggered recording where high-frequency reads/writes occur.
 * - Supports 30s of audio (16kHz) to comply with Whisper's 30-second window.
 */
class AudioCircularBuffer(val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var head = 0 // Points to the oldest sample
    private var size = 0 // Current number of samples in buffer
    private val lock = ReentrantLock()

    /**
     * Writes audio data to the buffer. Overwrites oldest data if full.
     */
    fun write(data: ShortArray, length: Int) {
        lock.withLock {
            for (i in 0 until length) {
                buffer[(head + size) % capacity] = data[i]
                if (size < capacity) {
                    size++
                } else {
                    head = (head + 1) % capacity
                }
            }
        }
    }

    /**
     * Reads a slice of audio data into a new ShortArray.
     * Use this for robust segment extraction with pre-roll.
     * 
     * @param offsetFromEnd Number of samples from the newest sample back to start from (e.g., 0 for current end).
     * @param length Number of samples to read back into the past.
     */
    fun readFromEnd(offsetFromEnd: Int, length: Int): ShortArray? {
        lock.withLock {
            if (length > size || offsetFromEnd + length > size) return null
            
            val result = ShortArray(length)
            val startIdx = (head + size - offsetFromEnd - length) % capacity
            val actualStart = if (startIdx < 0) startIdx + capacity else startIdx

            for (i in 0 until length) {
                result[i] = buffer[(actualStart + i) % capacity]
            }
            return result
        }
    }

    /**
     * Returns the entire valid contents of the buffer as a ShortArray.
     */
    fun readAll(): ShortArray {
        lock.withLock {
            val result = ShortArray(size)
            for (i in 0 until size) {
                result[i] = buffer[(head + i) % capacity]
            }
            return result
        }
    }

    fun clear() {
        lock.withLock {
            head = 0
            size = 0
        }
    }

    fun getSize() = size
}
