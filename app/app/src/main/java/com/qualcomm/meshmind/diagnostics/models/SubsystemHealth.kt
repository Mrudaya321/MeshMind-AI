package com.qualcomm.meshmind.diagnostics.models

/**
 * Immutable diagnostic representation of a subsystem's health status.
 */
data class SubsystemHealth(
    val subsystemName: String,
    val isOperational: Boolean,
    val lastExecutionTimestamp: Long,
    val errorCount: Long,
    val diagnosticMessage: String
)
