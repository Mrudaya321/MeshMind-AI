package com.qualcomm.meshmind.utils

import android.os.Build

/**
 * Isolates Android OS version variances to expose unified behaviors to other subsystems.
 */
object CompatibilityUtils {

    /**
     * Checks if the device runs Android 13 (Tiramisu, API 33) or higher.
     */
    fun isAtLeastApi33(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Checks if the device runs Android 12 (S, API 31) or higher.
     */
    fun isAtLeastApi31(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /**
     * Checks if the device runs Android 10 (Q, API 29) or higher.
     */
    fun isAtLeastApi29(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
