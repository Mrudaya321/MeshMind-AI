package com.qualcomm.meshmind.core

import com.qualcomm.meshmind.core.runtime.BaseSubsystem
import com.qualcomm.meshmind.core.runtime.SubsystemManager
import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests to verify correct registration and initialization priority sequences.
 */
class SubsystemInitializationTest {

    private lateinit var manager: SubsystemManager

    @Before
    fun setUp() {
        manager = SubsystemManager.getInstance()
    }

    @Test
    fun testSubsystemSortingPriority() {
        val lowPrioritySubsystem = object : BaseSubsystem {
            override val subsystemId: String = "low_priority"
            override val initPriority: Int = 99
            override suspend fun initialize() {}
            override fun shutdown() {}
            override fun getHealth(): SubsystemHealth = SubsystemHealth(subsystemId, true, 0, 0, "")
        }

        val highPrioritySubsystem = object : BaseSubsystem {
            override val subsystemId: String = "high_priority"
            override val initPriority: Int = 1
            override suspend fun initialize() {}
            override fun shutdown() {}
            override fun getHealth(): SubsystemHealth = SubsystemHealth(subsystemId, true, 0, 0, "")
        }

        manager.registerSubsystem(lowPrioritySubsystem)
        manager.registerSubsystem(highPrioritySubsystem)

        val list = manager.getSubsystems()
        assertTrue(list.contains(lowPrioritySubsystem))
        assertTrue(list.contains(highPrioritySubsystem))
        
        // Assert sorting priority: high_priority (1) should come before low_priority (99)
        val sortedList = list.sortedBy { it.initPriority }
        assertEquals("high_priority", sortedList[0].subsystemId)
        assertEquals("low_priority", sortedList[sortedList.size - 1].subsystemId)
    }
}
