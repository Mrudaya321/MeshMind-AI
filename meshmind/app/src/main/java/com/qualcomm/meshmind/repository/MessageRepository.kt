package com.qualcomm.meshmind.repository

import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.database.DatabaseManager
import com.qualcomm.meshmind.database.MessageDao
import com.qualcomm.meshmind.database.MessageEntity

/**
 * Handles persistent message tracking, delivery states updates, and text queries.
 */
class MessageRepository : BaseRepository() {

    private val messageDao: MessageDao by lazy {
        ServiceLocator.get(DatabaseManager::class.java).getDatabase().messageDao()
    }

    suspend fun saveMessage(
        messageId: String,
        conversationId: String,
        senderNodeId: String,
        receiverNodeId: String,
        body: String,
        deliveryStatus: String = "Created",
        emergencyClassIndex: Int? = null,
        emergencyClassLabel: String? = null,
        emergencyConfidence: Double? = null,
        classificationTimestamp: Long? = null,
        taxonomyVersion: String? = null
    ) = executeIO {
        val message = MessageEntity(
            messageId = messageId,
            conversationId = conversationId,
            senderNodeId = senderNodeId,
            receiverNodeId = receiverNodeId,
            timestamp = System.currentTimeMillis(),
            body = body,
            deliveryStatus = deliveryStatus,
            relayNodeId = null,
            isRead = false,
            emergencyClassIndex = emergencyClassIndex,
            emergencyClassLabel = emergencyClassLabel,
            emergencyConfidence = emergencyConfidence,
            classificationTimestamp = classificationTimestamp,
            taxonomyVersion = taxonomyVersion
        )
        messageDao.insertMessage(message)
    }

    suspend fun getMessagesForConversation(conversationId: String): List<MessageEntity> = executeIO {
        messageDao.getMessagesForConversation(conversationId)
    }

    suspend fun updateMessageStatus(messageId: String, status: String) = executeIO {
        messageDao.updateMessageStatus(messageId, status)
    }

    suspend fun getMessage(messageId: String): MessageEntity? = executeIO {
        messageDao.getMessage(messageId)
    }

    suspend fun updateMessageRelayedBy(messageId: String, status: String, relayNodeId: String) = executeIO {
        messageDao.updateMessageRelay(messageId, status, relayNodeId)
    }

    suspend fun getUnreadCount(conversationId: String): Int = executeIO {
        messageDao.getUnreadCount(conversationId)
    }

    suspend fun searchMessages(query: String): List<MessageEntity> = executeIO {
        messageDao.searchMessages(query)
    }
}
