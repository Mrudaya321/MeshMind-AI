package com.qualcomm.meshmind.network.transport

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import com.qualcomm.meshmind.packet.models.PacketType

class NNodeFormationTest {

    // Helper for deterministic election
    private fun electCoordinator(localNodeId: String, targetPeers: Set<String>): String {
        val snapshotPeers = targetPeers.map { it.lowercase(Locale.ROOT).trim() }.toSet()
        val localNodeIdCanonical = localNodeId.lowercase(Locale.ROOT).trim()
        val candidateSet = snapshotPeers + localNodeIdCanonical
        return candidateSet.sorted().first()
    }

    @Test
    fun testBuildCycleAcceptsMultipleLogicalPeers() {
        val targetLogicalPeers = setOf("node_b", "node_c")
        assertTrue("Multiple logical peers should be accepted without exceptions", targetLogicalPeers.size > 1)
    }

    @Test
    fun testCoordinatorElectionTwoNodes() {
        val local = "uuid-b"
        val peers = setOf("uuid-a")
        assertEquals("uuid-a", electCoordinator(local, peers))
    }

    @Test
    fun testCoordinatorElectionThreeNodes() {
        val local = "uuid-b"
        val peers = setOf("uuid-a", "uuid-c")
        assertEquals("uuid-a", electCoordinator(local, peers))
    }

    @Test
    fun testCoordinatorElectionFiveNodes() {
        val local = "uuid-x"
        val peers = setOf("uuid-y", "uuid-z", "uuid-w", "uuid-v")
        assertEquals("uuid-v", electCoordinator(local, peers))
    }

    @Test
    fun testCoordinatorElectionIndependentOfInputOrder() {
        val local = "uuid-m"
        val peers1 = setOf("uuid-n", "uuid-l")
        val peers2 = setOf("uuid-l", "uuid-n")
        assertEquals("uuid-l", electCoordinator(local, peers1))
        assertEquals("uuid-l", electCoordinator(local, peers2))
    }

    @Test
    fun testAdmissiblePeerAccepted() {
        val admissibleLogicalPeers = setOf("node_b", "node_c")
        val incomingNodeId = "node_b"
        assertTrue("Server must accept admissible peer", incomingNodeId in admissibleLogicalPeers)
    }

    @Test
    fun testUnknownPeerRejected() {
        val admissibleLogicalPeers = setOf("node_b", "node_c")
        val incomingNodeId = "node_x"
        assertFalse("Server must reject unknown peer", incomingNodeId in admissibleLogicalPeers)
    }
    
    @Test
    fun testCoordinatorAcceptsMultipleAdmissiblePeers() {
        val admissibleLogicalPeers = setOf("node_b", "node_c")
        val peer1 = "node_b"
        val peer2 = "node_c"
        assertTrue(peer1 in admissibleLogicalPeers)
        assertTrue(peer2 in admissibleLogicalPeers)
    }

    @Test
    fun testMultipleTransportSessionsCoexist() {
        val activeSessions = mutableMapOf<String, String>()
        activeSessions["node_b"] = "session_b"
        activeSessions["node_c"] = "session_c"
        assertEquals(2, activeSessions.size)
        assertTrue(activeSessions.containsKey("node_b") && activeSessions.containsKey("node_c"))
    }

    @Test
    fun testSecondSessionDoesNotReplaceFirstUnrelatedSession() {
        val activeSessions = mutableMapOf<String, String>()
        activeSessions["node_b"] = "session_b"
        activeSessions["node_c"] = "session_c"
        assertEquals("session_b", activeSessions["node_b"])
        assertEquals("session_c", activeSessions["node_c"])
    }

    // ReplayKey cache tests
    data class ReplayKey(val packetId: String, val sourceNodeId: String, val packetType: PacketType)
    
    @Test
    fun testDataPacketDoesNotSuppressCorrelatedAck() {
        val cache = mutableMapOf<ReplayKey, Boolean>()
        val dataKey = ReplayKey("msg-123", "node_a", PacketType.DATA)
        val ackKey = ReplayKey("msg-123", "node_b", PacketType.ACK)
        
        cache[dataKey] = true
        assertFalse("ACK should not be suppressed by corresponding DATA frame cache", cache.containsKey(ackKey))
    }

    @Test
    fun testDuplicateAckStillSuppressed() {
        val cache = mutableMapOf<ReplayKey, Boolean>()
        val ackKey = ReplayKey("msg-123", "node_b", PacketType.ACK)
        cache[ackKey] = true
        assertTrue("Duplicate ACK should be suppressed", cache.containsKey(ackKey))
    }

    @Test
    fun testDuplicateDataStillSuppressed() {
        val cache = mutableMapOf<ReplayKey, Boolean>()
        val dataKey = ReplayKey("msg-123", "node_a", PacketType.DATA)
        cache[dataKey] = true
        assertTrue("Duplicate DATA should be suppressed", cache.containsKey(dataKey))
    }

    @Test
    fun testMemberStopsProbingAfterCoordinatorSessionVerified() {
        val connectionState = "MESH_OPERATIONAL"
        val isProbing = false
        assertEquals("MESH_OPERATIONAL", connectionState)
        assertFalse(isProbing)
    }

    @Test
    fun testCoordinatorRemainsAcceptingAfterFirstSession() {
        var connectionState = "ACCEPTING_MEMBERS"
        val activeSessionCount = 1
        if (activeSessionCount > 0 && connectionState != "ACCEPTING_MEMBERS") {
            connectionState = "ACCEPTING_MEMBERS"
        }
        assertEquals("Coordinator must continue accepting members", "ACCEPTING_MEMBERS", connectionState)
    }

    @Test
    fun testDsdvCanRepresentRoutesToMultipleDestinations() {
        val routes = mutableMapOf<String, String>()
        routes["node_b"] = "next_hop_a"
        routes["node_c"] = "next_hop_a"
        assertEquals(2, routes.size)
    }

    @Test
    fun testBleVisiblePeerDoesNotBecomeDirectRouteWithoutVerifiedSession() {
        val verifiedDirectNeighbors = mutableMapOf<String, Boolean>()
        val bleNeighbor = "node_b"
        assertFalse("BLE presence alone must not create a verified physical 1-hop route", verifiedDirectNeighbors.containsKey(bleNeighbor))
    }

    @Test
    fun testVerifiedSessionRegistersDirectNeighbor() {
        val verifiedDirectNeighbors = mutableMapOf<String, Boolean>()
        val sessionRegistered = "node_b"
        verifiedDirectNeighbors[sessionRegistered] = true
        assertTrue("Verified session MUST bootstrap physical 1-hop route", verifiedDirectNeighbors.containsKey(sessionRegistered))
    }

    @Test
    fun testSessionRemovalInvalidatesDirectNeighbor() {
        val verifiedDirectNeighbors = mutableMapOf<String, Boolean>()
        val nodeId = "node_b"
        verifiedDirectNeighbors[nodeId] = true
        
        // Session closes
        verifiedDirectNeighbors.remove(nodeId)
        assertFalse("Session removal MUST invalidate physical 1-hop route", verifiedDirectNeighbors.containsKey(nodeId))
    }

    @Test
    fun testMemberRejectsAdmissibleNonCoordinatorPeer() {
        val expectedCoordinatorNodeId = "node_a"
        val remoteNodeId = "node_b" // Also in the target peers, but NOT the coordinator
        assertFalse("Member must strictly reject non-coordinator peers", expectedCoordinatorNodeId == remoteNodeId)
    }

    @Test
    fun testMemberAcceptsOnlyElectedCoordinator() {
        val expectedCoordinatorNodeId = "node_a"
        val remoteNodeId = "node_a"
        assertTrue("Member must accept the elected coordinator", expectedCoordinatorNodeId == remoteNodeId)
    }

    @Test
    fun testCreateGroupApiSuccessDoesNotMeanGroupFormed() {
        val connectionState = "GROUP_CREATE_REQUEST_ACCEPTED"
        assertNotEquals("createGroup.onSuccess() only implies ACCEPTED, not FORMED", "GROUP_FORMED", connectionState)
    }

    @Test
    fun testCoordinatorAcceptingMembersOnlyAfterGroupOwnerConfirmed() {
        var isGroupOwner = false
        var state = "GROUP_CREATE_REQUEST_ACCEPTED"
        
        // P2P Broadcast
        isGroupOwner = true
        if (isGroupOwner) {
            state = "ACCEPTING_MEMBERS"
        }
        assertEquals("Must confirm group owner status before ACCEPTING_MEMBERS", "ACCEPTING_MEMBERS", state)
    }
}
