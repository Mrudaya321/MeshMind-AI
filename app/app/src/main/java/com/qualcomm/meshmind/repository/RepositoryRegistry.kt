package com.qualcomm.meshmind.repository

import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.state.NeighborStateRepository

/**
 * Handles lazy registration and retrieval of all MeshMind repositories.
 */
object RepositoryRegistry {

    val neighborStateRepository: NeighborStateRepository by lazy { NeighborStateRepository.getInstance() }
    val conversationRepository: ConversationRepository by lazy { ConversationRepository() }
    val messageRepository: MessageRepository by lazy { MessageRepository() }
    val packetHistoryRepository: PacketHistoryRepository by lazy { PacketHistoryRepository() }
    val telemetryRepository: TelemetryRepository by lazy { TelemetryRepository() }
    val runtimeStatisticsRepository: RuntimeStatisticsRepository by lazy { RuntimeStatisticsRepository() }
    val diagnosticsRepository: DiagnosticsRepository by lazy { DiagnosticsRepository() }
    val emergencyEventRepository: EmergencyEventRepository by lazy { EmergencyEventRepository() }
    val routingStateRepository: RoutingStateRepository by lazy { RoutingStateRepository() }
    val packetQueueRepository: PacketQueueRepository by lazy { PacketQueueRepository() }

    /**
     * Publishes all repositories to the ServiceLocator container.
     */
    fun registerAll() {
        ServiceLocator.register(NeighborStateRepository::class.java, neighborStateRepository)
        ServiceLocator.register(ConversationRepository::class.java, conversationRepository)
        ServiceLocator.register(MessageRepository::class.java, messageRepository)
        ServiceLocator.register(PacketHistoryRepository::class.java, packetHistoryRepository)
        ServiceLocator.register(TelemetryRepository::class.java, telemetryRepository)
        ServiceLocator.register(RuntimeStatisticsRepository::class.java, runtimeStatisticsRepository)
        ServiceLocator.register(DiagnosticsRepository::class.java, diagnosticsRepository)
        ServiceLocator.register(EmergencyEventRepository::class.java, emergencyEventRepository)
        ServiceLocator.register(RoutingStateRepository::class.java, routingStateRepository)
        ServiceLocator.register(PacketQueueRepository::class.java, packetQueueRepository)
    }
}
