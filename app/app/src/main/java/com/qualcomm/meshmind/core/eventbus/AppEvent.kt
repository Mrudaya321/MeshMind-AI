package com.qualcomm.meshmind.core.eventbus

/**
 * Base abstract class for all strongly-typed events published via the EventBus.
 */
abstract class AppEvent(
    val eventType: String,
    val timestamp: Long = System.currentTimeMillis()
)
