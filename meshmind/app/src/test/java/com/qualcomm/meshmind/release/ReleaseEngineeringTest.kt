package com.qualcomm.meshmind.release

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ReleaseEngineeringTest {

    private lateinit var releaseManager: ReleaseManager

    @Before
    fun setUp() {
        releaseManager = ReleaseManager.getInstance()
        releaseManager.setActiveVariant(ReleaseManager.BuildVariant.DEVELOPMENT)
        releaseManager.setDeveloperModeEnabled(false)
    }

    @Test
    fun testVersionConfigurations() {
        assertEquals("1.2.0", ReleaseManager.APP_VERSION)
        assertEquals(2, ReleaseManager.PROTOCOL_VERSION)
    }

    @Test
    fun testSecureLoggingRedactor() {
        val testPayload = "Secret cryptographic emergency payload content"
        
        // 1. In release mode (devMode = false), payload should be redacted
        val releaseLog = releaseManager.redactLogPayload(testPayload)
        assertEquals("[REDACTED PAYLOAD - ENCRYPTED]", releaseLog)

        // 2. In developer mode (devMode = true), payload should be transparent
        releaseManager.setDeveloperModeEnabled(true)
        val devLog = releaseManager.redactLogPayload(testPayload)
        assertEquals(testPayload, devLog)
    }
}
