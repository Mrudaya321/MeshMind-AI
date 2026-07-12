package com.qualcomm.meshmind.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BuildVariantFlagTest {

    private lateinit var releaseManager: ReleaseManager

    @Before
    fun setUp() {
        releaseManager = ReleaseManager.getInstance()
    }

    @Test
    fun testProductionReleaseConfig() {
        releaseManager.setActiveVariant(ReleaseManager.BuildVariant.PRODUCTION_RELEASE)
        assertEquals(ReleaseManager.BuildVariant.PRODUCTION_RELEASE, releaseManager.getActiveVariant())
        assertFalse(releaseManager.isDeveloperModeEnabled())
        assertFalse(releaseManager.isDiagnosticsVisible())
        assertTrue(releaseManager.isTelemetryCompressionEnabled())

        val sensitivePayload = "Confidential SOS message"
        assertEquals("[REDACTED PAYLOAD - ENCRYPTED]", releaseManager.redactLogPayload(sensitivePayload))
    }

    @Test
    fun testDevelopmentConfig() {
        releaseManager.setActiveVariant(ReleaseManager.BuildVariant.DEVELOPMENT)
        assertEquals(ReleaseManager.BuildVariant.DEVELOPMENT, releaseManager.getActiveVariant())
        assertTrue(releaseManager.isDeveloperModeEnabled())
        assertTrue(releaseManager.isDiagnosticsVisible())
        assertFalse(releaseManager.isTelemetryCompressionEnabled())

        val sensitivePayload = "Confidential SOS message"
        assertEquals(sensitivePayload, releaseManager.redactLogPayload(sensitivePayload))
    }
}
