package com.qualcomm.meshmind.classification.models

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

data class EmergencyRoleAnnouncementV1(
    val schemaVersion: Int = 1,
    val announcementId: String,
    val canonicalNodeId: String,
    val responseRole: String, // String representation of EmergencyResponseRole
    val generation: Long,
    val createdAt: Long
)

object EmergencyRoleAnnouncementCodec {
    private val gson = Gson()

    fun encode(payload: EmergencyRoleAnnouncementV1): ByteArray {
        val jsonString = gson.toJson(payload)
        return jsonString.toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): EmergencyRoleAnnouncementV1? {
        val rawString = String(bytes, Charsets.UTF_8)
        return try {
            val payload = gson.fromJson(rawString, EmergencyRoleAnnouncementV1::class.java)
            if (payload != null &&
                payload.schemaVersion == 1 &&
                payload.announcementId.isNotBlank() &&
                payload.canonicalNodeId.isNotBlank() &&
                payload.responseRole.isNotBlank()) {
                payload
            } else {
                null
            }
        } catch (e: JsonSyntaxException) {
            null
        }
    }
}
