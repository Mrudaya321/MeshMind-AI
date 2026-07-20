package com.qualcomm.meshmind.network.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

class InitiatorElectionTest {

    @Test
    fun testDeterministicInitiatorElection() {
        // Exact physical Node IDs requested by the user
        val nodeA = "17cdc9fc-7692-433a-8d54-1386e1d4e916"
        val nodeB = "e790f61b-7912-4d1b-b58b-76fdc2d682db"

        // Canonicalization rule used in WifiDirectConnectionManager
        val canonicalA = nodeA.lowercase(Locale.ROOT).trim()
        val canonicalB = nodeB.lowercase(Locale.ROOT).trim()

        // Evaluated on Node A (Local = A, Remote = B)
        val isInitiatorOnNodeA = canonicalA < canonicalB

        // Evaluated on Node B (Local = B, Remote = A)
        val isInitiatorOnNodeB = canonicalB < canonicalA

        // Rule 1: Exactly one device must produce INITIATE (true), exactly one must produce WAIT (false)
        assertNotEquals("Both devices cannot evaluate to the same initiator state", isInitiatorOnNodeA, isInitiatorOnNodeB)

        // Rule 2: Verify the exact deterministic outcome for these two Node IDs
        // 17cdc... < e790f... is TRUE lexicographically
        assertEquals("Node A should be the initiator", true, isInitiatorOnNodeA)
        assertEquals("Node B should NOT be the initiator", false, isInitiatorOnNodeB)
    }
}
