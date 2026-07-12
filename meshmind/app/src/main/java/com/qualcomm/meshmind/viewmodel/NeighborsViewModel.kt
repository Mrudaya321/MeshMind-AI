package com.qualcomm.meshmind.viewmodel

import com.qualcomm.meshmind.models.NeighborNodeState
import com.qualcomm.meshmind.state.NeighborStateRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

/**
 * Connects the neighboring nodes monitor UI with hot-state repository updates in Kotlin.
 */
class NeighborsViewModel : BaseViewModel() {

    private val neighborRepo = NeighborStateRepository.getInstance()

    val neighbors: StateFlow<List<NeighborNodeState>> = neighborRepo.neighborListFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}
