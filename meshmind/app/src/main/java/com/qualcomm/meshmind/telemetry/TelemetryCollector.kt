package com.qualcomm.meshmind.telemetry

/**
 * Interface defining telemetry collection contracts in Kotlin.
 */
interface TelemetryCollector {

    /**
     * Collects and packages a fresh snapshot of raw system telemetry.
     */
    fun collectCurrentTelemetry(): RawTelemetry
}
