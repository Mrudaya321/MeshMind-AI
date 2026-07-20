package com.qualcomm.meshmind.state

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe local cache for neighbor reliability predictions.
 */
class PredictionCache private constructor() {

    private val predictions = ConcurrentHashMap<String, Double>()
    private val cacheTimestamps = ConcurrentHashMap<String, Long>()

    companion object {
        private const val CACHE_TTL_MS = 30000L // 30 seconds
        
        @Volatile
        private var instance: PredictionCache? = null

        fun getInstance(): PredictionCache {
            return instance ?: synchronized(this) {
                instance ?: PredictionCache().also { instance = it }
            }
        }
    }

    fun putPrediction(nodeId: String, reliability: Double) {
        predictions[nodeId] = reliability
        cacheTimestamps[nodeId] = System.currentTimeMillis()
    }

    /**
     * Retrieves the reliability score. Returns -1.0 if missing or expired.
     */
    fun getPrediction(nodeId: String): Double {
        val timestamp = cacheTimestamps[nodeId] ?: return -1.0
        if (System.currentTimeMillis() - timestamp > CACHE_TTL_MS) {
            predictions.remove(nodeId)
            cacheTimestamps.remove(nodeId)
            return -1.0
        }
        return predictions[nodeId] ?: -1.0
    }

    fun clear() {
        predictions.clear()
        cacheTimestamps.clear()
    }
}
