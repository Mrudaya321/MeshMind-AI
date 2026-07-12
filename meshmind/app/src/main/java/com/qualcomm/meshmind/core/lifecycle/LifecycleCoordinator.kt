package com.qualcomm.meshmind.core.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.qualcomm.meshmind.logging.MeshLogger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Monitors the application-wide process lifecycle and dispatches events to registered subsystems.
 * Uses ProcessLifecycleOwner to detect background/foreground transitions.
 */
class LifecycleCoordinator private constructor() : DefaultLifecycleObserver {

    private val listeners = CopyOnWriteArrayList<AppLifecycleListener>()
    
    @Volatile
    var isForeground: Boolean = false
        private set

    companion object {
        private const val TAG = "LifecycleCoordinator"
        
        @Volatile
        private var instance: LifecycleCoordinator? = null

        fun getInstance(): LifecycleCoordinator {
            return instance ?: synchronized(this) {
                instance ?: LifecycleCoordinator().also { instance = it }
            }
        }
    }

    /**
     * Connects this coordinator to the ProcessLifecycleOwner.
     * Must be called from the main thread during Application start.
     */
    fun startMonitoring() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        MeshLogger.i(TAG, "Process lifecycle monitoring started.")
    }

    fun registerLifecycleListener(listener: AppLifecycleListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            if (isForeground) {
                listener.onForegroundEntered()
            } else {
                listener.onBackgroundEntered()
            }
        }
    }

    fun unregisterLifecycleListener(listener: AppLifecycleListener) {
        listeners.remove(listener)
    }

    override fun onStart(owner: LifecycleOwner) {
        isForeground = true
        MeshLogger.i(TAG, "Application entered FOREGROUND")
        for (listener in listeners) {
            try {
                listener.onForegroundEntered()
            } catch (e: Exception) {
                MeshLogger.e(TAG, "Error invoking onForegroundEntered on listener", e)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground = false
        MeshLogger.i(TAG, "Application entered BACKGROUND")
        for (listener in listeners) {
            try {
                listener.onBackgroundEntered()
            } catch (e: Exception) {
                MeshLogger.e(TAG, "Error invoking onBackgroundEntered on listener", e)
            }
        }
    }
}
