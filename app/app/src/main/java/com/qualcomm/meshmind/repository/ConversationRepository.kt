package com.qualcomm.meshmind.repository

import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.database.ConversationDao
import com.qualcomm.meshmind.database.ConversationEntity
import com.qualcomm.meshmind.database.DatabaseManager

/**
 * Repository driving Conversation persistence.
 */
class ConversationRepository : BaseRepository() {

    private val conversationDao: ConversationDao by lazy {
        ServiceLocator.get(DatabaseManager::class.java).getDatabase().conversationDao()
    }

    suspend fun createConversation(conversationId: String, title: String) = executeIO {
        val conversation = ConversationEntity(
            conversationId = conversationId,
            title = title,
            lastActiveTimestamp = System.currentTimeMillis()
        )
        conversationDao.insertConversation(conversation)
    }

    suspend fun getAllConversations(): List<ConversationEntity> = executeIO {
        conversationDao.getAllConversations()
    }

    suspend fun getConversation(conversationId: String): ConversationEntity? = executeIO {
        conversationDao.getConversation(conversationId)
    }

    suspend fun deleteConversation(conversationId: String) = executeIO {
        conversationDao.deleteConversation(conversationId)
    }
}
