package com.qualcomm.meshmind.viewmodel

import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.database.ConversationEntity
import com.qualcomm.meshmind.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

/**
 * Manages reactive conversation lists.
 */
class ConversationsViewModel : BaseViewModel() {

    private val conversationRepo = ServiceLocator.get(ConversationRepository::class.java)

    private val _conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    val conversations: StateFlow<List<ConversationEntity>> = _conversations.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val list = conversationRepo.getAllConversations()
                _conversations.value = list
            } catch (ignored: Exception) {}
        }
    }
}
