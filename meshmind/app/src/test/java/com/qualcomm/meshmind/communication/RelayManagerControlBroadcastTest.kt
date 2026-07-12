package com.qualcomm.meshmind.communication

import com.qualcomm.meshmind.classification.EmergencyResponseDirectory
import com.qualcomm.meshmind.classification.models.EmergencyResponseRole
import com.qualcomm.meshmind.classification.models.EmergencyRoleAnnouncementCodec
import com.qualcomm.meshmind.classification.models.EmergencyRoleAnnouncementV1
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.identity.DeviceIdentityManager
import com.qualcomm.meshmind.network.transport.TransportManager
import com.qualcomm.meshmind.network.transport.BroadcastTransportResult
import com.qualcomm.meshmind.packet.builder.PacketBuilder
import com.qualcomm.meshmind.packet.models.MeshFrame
import com.qualcomm.meshmind.packet.models.PacketType
import com.qualcomm.meshmind.repository.MessageRepository
import com.qualcomm.meshmind.logging.MeshLogger
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.mockkObject
import io.mockk.just
import io.mockk.Runs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class RelayManagerControlBroadcastTest {

    private lateinit var relayManager: RelayManager
    private lateinit var mockIdentityManager: DeviceIdentityManager
    private lateinit var mockTransportManager: TransportManager
    private lateinit var mockMessageRepo: MessageRepository
    private lateinit var mockCommManager: ReliableCommunicationManager

    @Before
    fun setup() {
        mockIdentityManager = mockk()
        mockTransportManager = mockk()
        mockMessageRepo = mockk(relaxed = true)
        mockCommManager = mockk(relaxed = true)

        ServiceLocator.register(DeviceIdentityManager::class.java, mockIdentityManager)
        ServiceLocator.register(TransportManager::class.java, mockTransportManager)
        ServiceLocator.register(MessageRepository::class.java, mockMessageRepo)
        
        mockkObject(ReliableCommunicationManager)
        every { ReliableCommunicationManager.getInstance() } returns mockCommManager

        mockkObject(MeshLogger)
        every { MeshLogger.i(any(), any()) } just io.mockk.Runs
        every { MeshLogger.w(any(), any()) } just io.mockk.Runs
        every { MeshLogger.d(any(), any()) } just io.mockk.Runs
        every { MeshLogger.e(any(), any(), any()) } just io.mockk.Runs

        coEvery { mockIdentityManager.resolveNodeId() } returns "local_node"
        
        val result = BroadcastTransportResult(BroadcastTransportResult.BroadcastStatus.ALL_SENDS_SUCCEEDED, 1, 1, 0)
        coEvery { mockTransportManager.broadcastFrame(match { true }, match { true }) } returns result

        relayManager = RelayManager.getInstance()
        // Wait for initialize in runBlocking if needed, or hack it:
        val field = RelayManager::class.java.getDeclaredField("isOperational")
        field.isAccessible = true
        field.set(relayManager, true)
        
        val localNodeField = RelayManager::class.java.getDeclaredField("localNodeId")
        localNodeField.isAccessible = true
        localNodeField.set(relayManager, "local_node")
    }

    @After
    fun teardown() {
        ServiceLocator.clear()
        relayManager.shutdown()
        unmockkAll()
    }

    @Test
    fun testDataPlusBroadcastDestinationDoesNotEnterRoleAnnouncement() {
        val frame = PacketBuilder()
            .setSourceNodeId("nodeA")
            .setDestinationNodeId("BROADCAST")
            .setPacketType(PacketType.DATA)
            .setPayload(ByteArray(10))
            .setChecksum(0)
            .setPacketId("packet1")
            .build()
            
        relayManager.processFrame(frame)
        
        // Ensure broadcastFrame is NOT called on transportManager
        coVerify(exactly = 0) { mockTransportManager.broadcastFrame(match { true }, match { true }) }
    }

    @Test
    fun testEmergencyPlusBroadcastDestinationDoesNotEnterRoleAnnouncement() {
        val frame = PacketBuilder()
            .setSourceNodeId("nodeA")
            .setDestinationNodeId("BROADCAST")
            .setPacketType(PacketType.EMERGENCY)
            .setPayload(ByteArray(10))
            .setChecksum(0)
            .setPacketId("packet2")
            .build()
            
        relayManager.processFrame(frame)
        coVerify(exactly = 0) { mockTransportManager.broadcastFrame(match { true }, match { true }) }
    }
    
    @Test
    fun testMalformedControlBroadcastIsNotForwarded() {
        val frame = PacketBuilder()
            .setSourceNodeId("nodeA")
            .setDestinationNodeId("BROADCAST")
            .setPacketType(PacketType.CONTROL)
            .setPayload("Invalid JSON".toByteArray())
            .setChecksum(0)
            .setPacketId("packet3")
            .build()
            
        relayManager.processFrame(frame)
        coVerify(exactly = 0) { mockTransportManager.broadcastFrame(match { true }, match { true }) }
        coVerify(exactly = 0) { mockCommManager.processIncomingRawBytes(match { true }) }
    }

    @Test
    fun testValidControlBroadcastIsProcessedAndForwarded() {
        val payload = EmergencyRoleAnnouncementV1(
            announcementId = "ann123",
            canonicalNodeId = "nodeA",
            responseRole = EmergencyResponseRole.FIRE_DEPARTMENT.name,
            generation = 1,
            createdAt = System.currentTimeMillis()
        )
        val payloadBytes = EmergencyRoleAnnouncementCodec.encode(payload)
        
        val frame = PacketBuilder()
            .setSourceNodeId("nodeA")
            .setDestinationNodeId("BROADCAST")
            .setPacketType(PacketType.CONTROL)
            .setPayload(payloadBytes)
            .setChecksum(0)
            .setPacketId("ann123")
            .build()
            
        relayManager.processFrame(frame)
        
        // It must be forwarded
        coVerify(exactly = 1) { mockTransportManager.broadcastFrame(match { true }, match { true }) }
        // It must be locally processed
        coVerify(exactly = 1) { mockCommManager.processIncomingRawBytes(match { true }) }
    }
    
    @Test
    fun testDuplicateAnnouncementIdIsNotForwardedTwice() {
        val payload = EmergencyRoleAnnouncementV1(
            announcementId = "ann123",
            canonicalNodeId = "nodeA",
            responseRole = EmergencyResponseRole.FIRE_DEPARTMENT.name,
            generation = 1,
            createdAt = System.currentTimeMillis()
        )
        val payloadBytes = EmergencyRoleAnnouncementCodec.encode(payload)
        
        val frame1 = PacketBuilder()
            .setSourceNodeId("nodeA")
            .setDestinationNodeId("BROADCAST")
            .setPacketType(PacketType.CONTROL)
            .setPayload(payloadBytes)
            .setChecksum(0)
            .setPacketId("ann123")
            .build()
            
        relayManager.processFrame(frame1)
        
        // Second frame with same packetId, MUST have same original sourceNodeId
        val frame2 = PacketBuilder()
            .setSourceNodeId("nodeA") // Original source is nodeA, even if forwarded by nodeB
            .setDestinationNodeId("BROADCAST")
            .setPacketType(PacketType.CONTROL)
            .setPayload(payloadBytes)
            .setChecksum(0)
            .setPacketId("ann123")
            .build()
            
        relayManager.processFrame(frame2)
        
        // The mock TransportManager should only receive the broadcast ONCE
        coVerify(exactly = 1) { mockTransportManager.broadcastFrame(match { true }, match { true }) }
    }

    @Test
    fun testTtlZeroPreventsFurtherForwarding() {
        val payload = EmergencyRoleAnnouncementV1(
            announcementId = "ann123",
            canonicalNodeId = "nodeA",
            responseRole = EmergencyResponseRole.FIRE_DEPARTMENT.name,
            generation = 1,
            createdAt = System.currentTimeMillis()
        )
        val payloadBytes = EmergencyRoleAnnouncementCodec.encode(payload)
        
        val frame = PacketBuilder()
            .setSourceNodeId("nodeA")
            .setDestinationNodeId("BROADCAST")
            .setPacketType(PacketType.CONTROL)
            .setPayload(payloadBytes)
            .setChecksum(0)
            .setPacketId("ann123")
            .build()
            
        // Manually set TTL to 1 so decrement makes it 0
        val dyingFrame = frame.copy(ttl = 1)
        
        relayManager.processFrame(dyingFrame)
        
        // It should NOT be forwarded because TTL expired
        coVerify(exactly = 0) { mockTransportManager.broadcastFrame(match { true }, match { true }) }
    }
}
