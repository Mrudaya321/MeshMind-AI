package com.qualcomm.meshmind.core.runtime

import com.qualcomm.meshmind.diagnostics.models.SubsystemHealth

/**
 * Common contract that every architectural manager/subsystem within MeshMind must implement.
 * Provides lifecycle initialization, shutdown hooks, and diagnostic health reporting.
 */
interface BaseSubsystem {

    /**
     * Unique identifier for the subsystem.
     */
    val subsystemId: String

    /**
     * Initializes the subsystem.
     * Guaranteed to be called during application bootstrap according to dependency priority.
     */
    suspend fun initialize()

    /**
     * Gracefully stops the subsystem during application termination.
     * Should release any held system resources, files, database locks, or coroutine jobs.
     */
    fun shutdown()

    /**
     * Returns the execution priority. Smaller numbers initialize first.
     */
    val initPriority: Int

    /**
     * Fetches current health status, error counts, and operational readiness metrics.
     */
    fun getHealth(): SubsystemHealth
}
