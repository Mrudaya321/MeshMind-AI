package com.qualcomm.meshmind.communication

import com.qualcomm.meshmind.packet.models.MeshFrame
import java.util.PriorityQueue

/**
 * Thread-safe Priority Scheduler prioritizing frames (lower priority index = higher execution precedence).
 */
class PacketScheduler {

    private val lock = Any()
    
    // Sorts packets by ascending priority: 0 (EMERGENCY) -> 1 (ROUTING/ACK) -> 2 (DATA) -> 3 (TELEMETRY)
    private val priorityQueue = PriorityQueue<MeshFrame> { f1, f2 ->
        f1.priority.compareTo(f2.priority)
    }

    companion object {
        @Volatile
        private var instance: PacketScheduler? = null

        fun getInstance(): PacketScheduler {
            return instance ?: synchronized(this) {
                instance ?: PacketScheduler().also { instance = it }
            }
        }
    }

    /**
     * Enqueues a packet into the priority schedule.
     */
    fun enqueue(frame: MeshFrame) = synchronized(lock) {
        priorityQueue.offer(frame)
    }

    /**
     * Pulls the highest priority packet from the queue.
     */
    fun pollNext(): MeshFrame? = synchronized(lock) {
        priorityQueue.poll()
    }

    /**
     * Peeks the highest priority packet without removal.
     */
    fun peekNext(): MeshFrame? = synchronized(lock) {
        priorityQueue.peek()
    }

    fun size(): Int = synchronized(lock) {
        priorityQueue.size
    }

    fun clear() = synchronized(lock) {
        priorityQueue.clear()
    }
}
