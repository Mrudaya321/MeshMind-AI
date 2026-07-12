package com.qualcomm.meshmind.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VersionCompatibilityTest {

    private lateinit var versionManager: VersionManager

    @Before
    fun setUp() {
        versionManager = VersionManager.getInstance()
    }

    @Test
    fun testVersionConstants() {
        assertEquals("1.2.0", VersionManager.APP_VERSION)
        assertEquals(2, VersionManager.PROTOCOL_VERSION)
        assertEquals(1, VersionManager.MODEL_VERSION)
        assertEquals(1, VersionManager.TELEMETRY_SCHEMA_VERSION)
    }

    @Test
    fun testProtocolNegotiations() {
        // Same version is compatible
        assertTrue(versionManager.isProtocolCompatible(2))
        
        // Older version 1 fallback is compatible (backward compatibility)
        assertTrue(versionManager.isProtocolCompatible(1))
        
        // Future higher protocol version is incompatible
        assertFalse(versionManager.isProtocolCompatible(3))
    }

    @Test
    fun testModelCompatibility() {
        assertTrue(versionManager.isModelCompatible(1))
        assertFalse(versionManager.isModelCompatible(2))
    }
}
