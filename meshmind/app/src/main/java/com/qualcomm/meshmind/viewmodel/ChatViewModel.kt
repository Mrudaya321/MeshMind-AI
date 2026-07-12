package com.qualcomm.meshmind.viewmodel

import com.qualcomm.meshmind.communication.ReliableCommunicationManager
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.database.MessageEntity
import com.qualcomm.meshmind.repository.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

/**
 * Connects the Chat Experience Composer with the Message Repository in Kotlin.
 */
import com.qualcomm.meshmind.ui.presentation.EmergencyPresentationMapper

data class ChatMessageUiState(
    val entity: MessageEntity,
    val showEmergencyTag: Boolean,
    val emergencyColorResId: Int? = null,
    val emergencyIconResId: Int? = null,
    val formattedConfidence: String? = null
)

class ChatViewModel(private val conversationId: String) : BaseViewModel() {

    private val messageRepo = ServiceLocator.get(MessageRepository::class.java)
    
    private val _messages = MutableStateFlow<List<ChatMessageUiState>>(emptyList())
    val messages: StateFlow<List<ChatMessageUiState>> = _messages.asStateFlow()

    init {
        loadMessages()
    }

    fun loadMessages() {
        viewModelScope.launch {
            try {
                // Reload or observe messages from persistence
                val list = messageRepo.getMessagesForConversation(conversationId)
                _messages.value = list.map { entity ->
                    val hasTag = entity.emergencyClassIndex != null && 
                                 entity.emergencyConfidence != null && 
                                 entity.emergencyConfidence >= EmergencyPresentationMapper.HIGH_CONFIDENCE_HIGHLIGHT_THRESHOLD
                                 
                    if (hasTag) {
                        val visual = EmergencyPresentationMapper.getVisualTreatment(entity.emergencyClassIndex!!)
                        ChatMessageUiState(
                            entity = entity,
                            showEmergencyTag = true,
                            emergencyColorResId = visual.first,
                            emergencyIconResId = visual.second,
                            formattedConfidence = EmergencyPresentationMapper.formatConfidence(entity.emergencyConfidence!!)
                        )
                    } else {
                        ChatMessageUiState(entity = entity, showEmergencyTag = false)
                    }
                }
            } catch (ignored: Exception) {}
        }
    }

    suspend fun sendMessage(text: String, isEmergency: Boolean = false, traceId: String? = null): Boolean {
        val identityMgr = ServiceLocator.get(com.qualcomm.meshmind.identity.DeviceIdentityManager::class.java)
        val sender = identityMgr.resolveNodeId().lowercase(java.util.Locale.ROOT).trim()
        val canonicalDest = conversationId.lowercase(java.util.Locale.ROOT).trim()

        traceId?.let {
            com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                traceId = it,
                sourceNodeId = sender,
                destinationNodeId = canonicalDest,
                stage = "CHAT_VIEWMODEL_INVOKED"
            )
        }

        val result = ReliableCommunicationManager.getInstance().sendTextMessage(
            source = sender,
            destination = canonicalDest,
            text = text,
            isEmergency = isEmergency,
            traceId = traceId
        )

        // Reload message log after scheduling only if successful
        if (result is com.qualcomm.meshmind.communication.SendResult.Enqueued) {
            loadMessages()
            return true
        }
        return false
    }
}
