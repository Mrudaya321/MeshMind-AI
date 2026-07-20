package com.qualcomm.meshmind.classification

import com.qualcomm.meshmind.classification.models.EmergencyResponseRole
import com.qualcomm.meshmind.communication.ReliableCommunicationManager
import com.qualcomm.meshmind.communication.SendResult
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.identity.DeviceIdentityManager
import com.qualcomm.meshmind.network.routing.RoutingEngine
import com.qualcomm.meshmind.logging.MeshLogger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import io.mockk.coVerify
import io.mockk.unmockkAll
import io.mockk.just
import io.mockk.Runs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class EmergencyBroadcastManagerTest {

    private lateinit var mockClassifier: EmergencyClassifier
    private lateinit var mockRoutingEngine: RoutingEngine
    private lateinit var mockIdentityManager: DeviceIdentityManager
    private lateinit var mockCommManager: ReliableCommunicationManager

    @Before
    fun setup() {
        mockClassifier = mockk()
        mockRoutingEngine = mockk()
        mockIdentityManager = mockk()
        mockCommManager = mockk()

        ServiceLocator.register(EmergencyClassifier::class.java, mockClassifier)
        ServiceLocator.register(RoutingEngine::class.java, mockRoutingEngine)
        ServiceLocator.register(DeviceIdentityManager::class.java, mockIdentityManager)

        mockkObject(ReliableCommunicationManager)
        every { ReliableCommunicationManager.getInstance() } returns mockCommManager

        mockkObject(MeshLogger)
        every { MeshLogger.i(any(), any()) } just io.mockk.Runs
        every { MeshLogger.w(any(), any()) } just io.mockk.Runs
        every { MeshLogger.d(any(), any()) } just io.mockk.Runs
        every { MeshLogger.e(any(), any(), any()) } just io.mockk.Runs

        coEvery { mockIdentityManager.resolveNodeId() } returns "nodeA"
    }

    @After
    fun teardown() {
        ServiceLocator.clear()
        unmockkAll()
        EmergencyResponseDirectory.clear()
    }

    @Test
    fun testUnknownEmergencyLabelProducesNoResponderSend() = runBlocking {
        // Mock classification to return an unmapped label
        coEvery { mockClassifier.classify("Aliens!") } returns EmergencyClassificationResult.Classified(9, "Alien Invasion", 0.99)

        val result = EmergencyBroadcastManager.broadcastEmergency("Aliens!")

        assertFalse(result.success)
        assertEquals("MAPPING_FAILED", result.status)
        assertNull(result.targetRole)
        assertNull(result.destinationNodeId)

        coVerify(exactly = 0) { mockCommManager.sendEmergencyBroadcast(match { true }, match { true }) }
    }

    @Test
    fun testEmergencyBroadcastSelectsConcreteResponder() = runBlocking {
        // Setup mock classification
        coEvery { mockClassifier.classify("Fire!") } returns EmergencyClassificationResult.Classified(0, "Fire", 0.95)

        // Setup directory with two responders
        EmergencyResponseDirectory.updateRole("responder1", EmergencyResponseRole.FIRE_DEPARTMENT, 1)
        EmergencyResponseDirectory.updateRole("responder2", EmergencyResponseRole.FIRE_DEPARTMENT, 1)

        // Setup routing (responder2 is closer)
        every { mockRoutingEngine.getHopCount("responder1") } returns 3
        every { mockRoutingEngine.getHopCount("responder2") } returns 2

        coEvery { mockCommManager.sendEmergencyBroadcast("responder2", match { true }) } returns SendResult.Enqueued("packet123")

        val result = EmergencyBroadcastManager.broadcastEmergency("Fire!")

        assertTrue(result.success)
        assertEquals("ENQUEUED", result.status)
        assertEquals(EmergencyResponseRole.FIRE_DEPARTMENT, result.targetRole)
        assertEquals("responder2", result.destinationNodeId) // Selected the closer one

        // Verify the exact call uses a concrete destination
        coVerify { mockCommManager.sendEmergencyBroadcast("responder2", match { true }) }
        coVerify(exactly = 0) { mockCommManager.sendEmergencyBroadcast("BROADCAST", match { true }) }
    }
}
