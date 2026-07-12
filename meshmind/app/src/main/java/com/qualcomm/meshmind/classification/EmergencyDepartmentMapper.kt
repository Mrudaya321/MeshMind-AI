package com.qualcomm.meshmind.classification

import com.qualcomm.meshmind.classification.models.EmergencyResponseRole

object EmergencyDepartmentMapper {

    /**
     * Deterministically maps an authoritative ONNX Emergency classification label
     * to a logical application-defined EmergencyResponseRole.
     */
    fun mapClassToDepartment(classLabel: String): EmergencyResponseRole? {
        return when (classLabel) {
            "Fire" -> EmergencyResponseRole.FIRE_DEPARTMENT
            "Flood" -> EmergencyResponseRole.DISASTER_RESPONSE
            "Earthquake" -> EmergencyResponseRole.DISASTER_RESPONSE
            "Storm" -> EmergencyResponseRole.DISASTER_RESPONSE
            "Building Collapse" -> EmergencyResponseRole.RESCUE_RESPONSE
            "Medical Emergency" -> EmergencyResponseRole.MEDICAL_RESPONSE
            "Security Threat" -> EmergencyResponseRole.SECURITY_RESPONSE
            "Chemical Explosion" -> EmergencyResponseRole.HAZMAT_RESPONSE
            else -> null // Unknown classes must explicitly fail
        }
    }

    /**
     * Returns the human-readable presentation label for the given department role.
     */
    fun getDepartmentDisplayName(role: EmergencyResponseRole): String {
        return when (role) {
            EmergencyResponseRole.FIRE_DEPARTMENT -> "Fire Department"
            EmergencyResponseRole.DISASTER_RESPONSE -> "Disaster Response"
            EmergencyResponseRole.RESCUE_RESPONSE -> "Rescue Response"
            EmergencyResponseRole.MEDICAL_RESPONSE -> "Medical Response"
            EmergencyResponseRole.SECURITY_RESPONSE -> "Security Response"
            EmergencyResponseRole.HAZMAT_RESPONSE -> "Hazmat Response"
            EmergencyResponseRole.CIVILIAN -> "Civilian"
        }
    }
}
