package com.qualcomm.meshmind.viewmodel

import com.qualcomm.meshmind.arduino.InfrastructureRepository
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.database.AppDatabase
import com.qualcomm.meshmind.database.DatabaseManager
import com.qualcomm.meshmind.repository.ConversationRepository
import com.qualcomm.meshmind.repository.MessageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class UiStateTest {

    @Before
    fun setUp() {
        // Tests rely on empty default constructors if ServiceLocator fails or we just bypass ServiceLocator
        // in ViewModel init. The ViewModels just initialize StateFlows.

        InfrastructureRepository.getInstance().clear()
    }

    @Test
    fun testDashboardViewModelInit() {
        val viewModel = DashboardViewModel()
        assertNotNull(viewModel.meshStatus.value)
        assertNotNull(viewModel.discoveredPeersCount.value)
        assertNotNull(viewModel.connectedPeersCount.value)
    }

    @Test
    fun testConversationsViewModelInit() {
        val viewModel = ConversationsViewModel()
        assertNotNull(viewModel.conversations.value)
        assertEquals(0, viewModel.conversations.value.size)
    }

    @Test
    fun testNeighborsViewModelInit() {
        val viewModel = NeighborsViewModel()
        assertNotNull(viewModel.neighbors.value)
        assertEquals(0, viewModel.neighbors.value.size)
    }

    @Test
    fun testInfrastructureViewModelInit() {
        val viewModel = InfrastructureViewModel()
        assertNotNull(viewModel.infrastructureNodes.value)
        assertEquals(0, viewModel.infrastructureNodes.value.size)
    }
}
