package com.qualcomm.meshmind.core.eventbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Reactive Event Bus utilizing Kotlin SharedFlow for decoupled asynchronous messaging.
 */
class EventBus private constructor() {

    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    companion object {
        @Volatile
        private var instance: EventBus? = null

        fun getInstance(): EventBus {
            return instance ?: synchronized(this) {
                instance ?: EventBus().also { instance = it }
            }
        }
    }

    /**
     * Publishes an event to the bus.
     */
    fun publish(event: AppEvent) {
        _events.tryEmit(event)
    }

    /**
     * Helper inline function to collect specific events inside a CoroutineScope.
     */
    inline fun <reified T : AppEvent> subscribe(scope: CoroutineScope, crossinline onEvent: (T) -> Unit) {
        scope.launch {
            events.filterIsInstance<T>().collect {
                onEvent(it)
            }
        }
    }
}
