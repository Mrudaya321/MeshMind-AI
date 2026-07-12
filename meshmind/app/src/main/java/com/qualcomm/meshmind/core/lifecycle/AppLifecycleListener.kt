package com.qualcomm.meshmind.core.lifecycle

/**
 * Interface that subsystems can implement to receive foreground and background transitions.
 */
interface AppLifecycleListener {

    /**
     * Triggered when the application moves from the background to the foreground.
     */
    fun onForegroundEntered()

    /**
     * Triggered when the application transitions to the background.
     */
    fun onBackgroundEntered()
}
