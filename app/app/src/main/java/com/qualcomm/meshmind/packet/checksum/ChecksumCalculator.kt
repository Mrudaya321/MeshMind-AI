package com.qualcomm.meshmind.packet.checksum

import java.util.zip.CRC32

/**
 * Calculates payload checksums for packet integrity checks.
 */
object ChecksumCalculator {

    /**
     * Computes the standard CRC32 checksum of a byte buffer.
     */
    fun calculateCrc32(buffer: ByteArray?): Long {
        if (buffer == null || buffer.isEmpty()) return 0
        return CRC32().apply {
            update(buffer)
        }.value
    }
}
