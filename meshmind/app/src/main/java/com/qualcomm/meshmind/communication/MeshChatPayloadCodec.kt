package com.qualcomm.meshmind.communication

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

data class MeshChatPayloadV1(
    val schemaVersion: Int = 1,
    val originalText: String,
    val emergencyClassIndex: Int? = null,
    val emergencyClassLabel: String? = null,
    val emergencyConfidence: Double? = null,
    val taxonomyVersion: String? = null
)

object MeshChatPayloadCodec {
    private val gson = Gson()

    fun encode(payload: MeshChatPayloadV1): ByteArray {
        val jsonString = gson.toJson(payload)
        return jsonString.toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): MeshChatPayloadV1 {
        val rawString = String(bytes, Charsets.UTF_8)
        return try {
            val payload = gson.fromJson(rawString, MeshChatPayloadV1::class.java)
            if (payload != null && payload.originalText != null && payload.schemaVersion == 1) {
                payload
            } else {
                // Fallback to raw text if JSON is valid but not our expected schema
                MeshChatPayloadV1(originalText = rawString)
            }
        } catch (e: JsonSyntaxException) {
            // Fallback to raw text (legacy nodes)
            MeshChatPayloadV1(originalText = rawString)
        }
    }
}
