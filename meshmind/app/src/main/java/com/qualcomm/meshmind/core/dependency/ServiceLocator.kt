package com.qualcomm.meshmind.core.dependency

import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight Service Locator implementation for MeshMind.
 * Avoids heavyweight dependency injection frameworks while guaranteeing clean,
 * testable class interactions in Kotlin.
 */
object ServiceLocator {

    private val services = ConcurrentHashMap<Class<*>, Any>()

    /**
     * Registers a service implementation.
     */
    fun <T : Any> register(serviceClass: Class<T>, implementation: T) {
        services[serviceClass] = implementation
    }

    /**
     * Fetches a registered service. Throws exception if not registered.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(serviceClass: Class<T>): T {
        val implementation = services[serviceClass] ?: throw IllegalStateException(
            "Service not registered: ${serviceClass.name}. Enforce proper initialization order in application startup."
        )
        return implementation as T
    }

    /**
     * Inline helper for idiomatic lookup: val storage = ServiceLocator.get<StorageManager>()
     */
    inline fun <reified T : Any> get(): T {
        return get(T::class.java)
    }

    /**
     * Resets the registry. Primarily useful for unit testing overrides.
     */
    fun clear() {
        services.clear()
    }
}
