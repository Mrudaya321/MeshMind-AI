package com.qualcomm.meshmind.core.scheduling

import com.qualcomm.meshmind.logging.MeshLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Task Scheduler driven by Kotlin Coroutines structured concurrency.
 * Supports periodic job runs with delays and randomization parameters (jitter).
 */
class TaskScheduler private constructor() {

    private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val random = Random.Default

    companion object {
        private const val TAG = "TaskScheduler"
        
        @Volatile
        private var instance: TaskScheduler? = null

        fun getInstance(): TaskScheduler {
            return instance ?: synchronized(this) {
                instance ?: TaskScheduler().also { instance = it }
            }
        }
    }

    /**
     * Schedules a recurring coroutine block.
     * Incorporates jitter to prevent synchronization resource spikes on the NPU/CPU.
     */
    @Synchronized fun schedulePeriodic(taskId: String, periodMs: Long, jitterFactor: Double, task: suspend () -> Unit) {
        cancel(taskId)

        val job = schedulerScope.launch {
            // Stagger startup using initial random delay
            val initialDelay = (periodMs * random.nextDouble()).toLong().coerceAtLeast(10)
            delay(initialDelay)

            while (isActive) {
                try {
                    task()
                } catch (e: Exception) {
                    MeshLogger.e(TAG, "Error running periodic task: $taskId", e)
                }

                // Compute next interval with added jitter
                val jitter = (periodMs * jitterFactor * (random.nextDouble() - 0.5) * 2).toLong()
                val nextDelay = (periodMs + jitter).coerceAtLeast(10)
                
                delay(nextDelay)
            }
        }

        activeJobs[taskId] = job
        MeshLogger.i(TAG, "Scheduled periodic coroutine task: $taskId (Interval: ${periodMs}ms, Jitter: ${jitterFactor * 100}%)")
    }

    /**
     * Cancels a scheduled task job.
     */
    @Synchronized fun cancel(taskId: String) {
        activeJobs.remove(taskId)?.let { job ->
            job.cancel()
            MeshLogger.i(TAG, "Cancelled periodic coroutine task: $taskId")
        }
    }

    fun shutdown() {
        activeJobs.keys.forEach { cancel(it) }
        MeshLogger.i(TAG, "TaskScheduler coroutines shutdown complete.")
    }
}
