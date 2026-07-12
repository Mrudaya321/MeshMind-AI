package com.qualcomm.meshmind.network.heartbeat

/**
 * Interface managing periodic local neighbor presence advertisements in Kotlin.
 */
interface HeartbeatAdvertiser {

    /**
     * Commences periodic beacon packet broadcasts.
     */
    fun startHeartbeats()

    /**
     * Halts beacon broadcasts.
     */
    fun stopHeartbeats()
}
