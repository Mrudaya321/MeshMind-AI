package com.qualcomm.meshmind.digitaltwin

import com.qualcomm.meshmind.digitaltwin.engine.DigitalTwinEngine
import com.qualcomm.meshmind.digitaltwin.engine.PacketTraceEngine
import com.qualcomm.meshmind.digitaltwin.engine.TopologyReconstructor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class DigitalTwinEngineTest {

    private lateinit var twinEngine: DigitalTwinEngine

    @Before
    fun setUp() {
        twinEngine = DigitalTwinEngine.getInstance()
        twinEngine.clear()
        TopologyReconstructor.getInstance().clear()
        PacketTraceEngine.getInstance().clear()
    }

    @Test
    fun testProcessTelemetrySnapshot() {
        val payload = "SNAPSHOT\nnodeId:node_test_twin\nuptimeMs:5000\ntimestamp:${System.currentTimeMillis()}\nneighbors:3\nroutes:4\n"
        twinEngine.processTelemetryPayload(payload)

        val node = twinEngine.getNode("node_test_twin")
        assertNotNull(node)
        assertEquals("node_test_twin", node?.nodeId)
        assertEquals(3, node?.neighborsCount)
        assertEquals(4, node?.routesCount)

        val links = TopologyReconstructor.getInstance().getReconstructedLinks()
        assertEquals(3, links.size)
    }

    @Test
    fun testProcessTelemetryEvent() {
        val payload = "EVENT:node_test_twin:NeighborDiscovered:Found new node neighbor_node_1:${System.currentTimeMillis()}"
        twinEngine.processTelemetryPayload(payload)

        val timeline = twinEngine.getTimeline()
        assertEquals(1, timeline.size)
        assertEquals("NeighborDiscovered", timeline[0].name)
    }
}
