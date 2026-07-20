package com.qualcomm.meshmind.ui.presentation

import android.content.Context
import androidx.core.content.ContextCompat
import com.qualcomm.meshmind.R

object EmergencyPresentationMapper {

    const val HIGH_CONFIDENCE_HIGHLIGHT_THRESHOLD = 0.75

    /**
     * PRESENTATION_MAPPING_IS_APPLICATION_DEFINED
     * Returns a tuple of (ColorResId, IconResId) based on the classification index.
     * The `label_map.json` defines taxonomy only and does not contain severity, color, icon, priority, or urgency.
     * This mapping is strictly a local UI presentation emphasis level rather than model-derived severity.
     * 
     * Index mappings from label_map.json:
     * 0: Fire
     * 1: Flood
     * 2: Earthquake
     * 3: Storm
     * 4: Building Collapse
     * 5: Medical Emergency
     * 6: Security Threat
     * 7: Chemical Explosion
     */
    fun getVisualTreatment(classIndex: Int): Pair<Int, Int> {
        return when (classIndex) {
            0 -> Pair(android.R.color.holo_red_dark, android.R.drawable.ic_dialog_alert)
            1 -> Pair(android.R.color.holo_blue_dark, android.R.drawable.ic_dialog_map)
            2 -> Pair(android.R.color.holo_orange_dark, android.R.drawable.ic_dialog_info)
            3 -> Pair(android.R.color.holo_blue_light, android.R.drawable.ic_dialog_dialer)
            4 -> Pair(android.R.color.darker_gray, android.R.drawable.ic_dialog_alert)
            5 -> Pair(android.R.color.holo_red_light, android.R.drawable.ic_menu_add) // Simple cross substitute
            6 -> Pair(android.R.color.black, android.R.drawable.ic_secure)
            7 -> Pair(android.R.color.holo_purple, android.R.drawable.ic_delete)
            else -> Pair(android.R.color.darker_gray, android.R.drawable.ic_dialog_info)
        }
    }

    fun formatConfidence(confidence: Double): String {
        return "${(confidence * 100).toInt()}%"
    }
}
