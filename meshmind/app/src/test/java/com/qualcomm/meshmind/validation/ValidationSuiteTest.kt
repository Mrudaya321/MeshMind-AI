package com.qualcomm.meshmind.validation

import com.qualcomm.meshmind.state.NeighborStateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class ValidationSuiteTest {

    private lateinit var simulator: LargeScaleSimulator
    private lateinit var injector: FaultInjector

    @Before
    fun setUp() {
        simulator = LargeScaleSimulator.getInstance()
        injector = FaultInjector.getInstance()
        NeighborStateRepository.getInstance().clear()
    }

    @Test
    fun testChainTopologySimulation() {
        // Build simulated 10-node chain
        simulator.createChainTopology(10)
        
        val neighbors = NeighborStateRepository.getInstance().getAllNeighbors()
        assertEquals(10, neighbors.size)

        val firstNode = NeighborStateRepository.getInstance().getNeighbor("virtual_node_1")
        assertNotNull(firstNode)
        assertEquals("virtual_node_1", firstNode?.nodeId)
    }

    @Test
    fun testFaultInjectionCampaign() {
        // Spawn nodes
        simulator.createChainTopology(5)
        
        val target = "virtual_node_3"
        val existing = NeighborStateRepository.getInstance().getNeighbor(target)
        assertNotNull(existing)

        // Inject link break
        injector.injectNeighborLoss(target)

        val updated = NeighborStateRepository.getInstance().getNeighbor(target)
        assertNotNull(updated)
        assertEquals(0.0, updated?.stabilityIndex ?: 1.0, 0.01)
    }
}
