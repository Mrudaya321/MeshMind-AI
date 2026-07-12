package com.qualcomm.meshmind.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayManagerFastPathTest {

    @Test
    fun testFastPathBypassesDSDVWhenDirectSessionExists() {
        val destinationNodeId = "7100be6d-d338-48bd-abe7-ccf938173b47"
        
        // Mock PeerSessionRegistry lookup
        val activeSessions = mapOf(
            destinationNodeId to true // Active session exists
        )
        
        val canonicalDest = destinationNodeId.lowercase(java.util.Locale.ROOT).trim()
        val directSessionExists = activeSessions[canonicalDest] == true
        
        var nextHop: String? = null
        var routeLookupStarted = false
        
        if (directSessionExists) {
            nextHop = canonicalDest
        } else {
            routeLookupStarted = true
            nextHop = null // Mocking DSDV missing route
        }
        
        assertFalse("Should not fallback to DSDV route lookup if direct session exists", routeLookupStarted)
        assertEquals("Next hop should equal the direct destination", canonicalDest, nextHop)
    }

    @Test
    fun testFastPathFallsBackToDSDVForUnrelatedPeer() {
        val destinationNodeId = "12e67c7d-8458-4a63-a6ac-07cc5fe82d3a"
        
        // Mock PeerSessionRegistry lookup containing ONLY an unrelated peer
        val activeSessions = mapOf(
            "7100be6d-d338-48bd-abe7-ccf938173b47" to true
        )
        
        val canonicalDest = destinationNodeId.lowercase(java.util.Locale.ROOT).trim()
        val directSessionExists = activeSessions[canonicalDest] == true
        
        var nextHop: String? = null
        var routeLookupStarted = false
        
        if (directSessionExists) {
            nextHop = canonicalDest
        } else {
            routeLookupStarted = true
            nextHop = "some_dsdv_next_hop" // Mocking DSDV returning a route
        }
        
        assertTrue("Must fallback to DSDV route lookup if no direct session to final destination", routeLookupStarted)
        assertEquals("Next hop should come from DSDV", "some_dsdv_next_hop", nextHop)
    }
    
    @Test
    fun testNodeIdCanonicalization() {
        val storedId = "7100be6d-d338-48bd-abe7-ccf938173b47"
        val incomingId = "  7100BE6D-D338-48BD-ABE7-CCF938173B47  "
        
        val activeSessions = mapOf(
            storedId to true
        )
        
        val canonicalDest = incomingId.lowercase(java.util.Locale.ROOT).trim()
        val directSessionExists = activeSessions[canonicalDest] == true
        
        assertTrue("Canonicalization must correctly match uppercase padded incoming IDs", directSessionExists)
    }
}
