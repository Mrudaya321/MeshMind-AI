package com.qualcomm.meshmind.viewmodel

import com.qualcomm.meshmind.arduino.InfrastructureNode
import com.qualcomm.meshmind.arduino.InfrastructureRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

/**
 * Manages presentation state for Arduino static beacons.
 */
class InfrastructureViewModel : BaseViewModel() {

    private val repo = InfrastructureRepository.getInstance()

    val infrastructureNodes: StateFlow<List<InfrastructureNode>> = repo.nodesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}
