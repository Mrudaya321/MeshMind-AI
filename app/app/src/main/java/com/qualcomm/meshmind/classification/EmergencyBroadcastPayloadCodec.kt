package com.qualcomm.meshmind.classification

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.qualcomm.meshmind.classification.models.EmergencyBroadcastPayloadV1

object EmergencyBroadcastPayloadCodec {
    private val gson = Gson()

    fun encode(payload: EmergencyBroadcastPayloadV1): ByteArray {
        val jsonString = gson.toJson(payload)
        return jsonString.toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): EmergencyBroadcastPayloadV1? {
        val rawString = String(bytes, Charsets.UTF_8)
        return try {
            val payload = gson.fromJson(rawString, EmergencyBroadcastPayloadV1::class.java)
            if (payload != null && 
                payload.schemaVersion == 1 &&
                payload.emergencyId.isNotBlank() &&
                payload.originNodeId.isNotBlank() &&
                payload.predictedClassIndex in 0..7 &&
                payload.predictedClassLabel.isNotBlank() &&
                payload.confidence in 0.0..1.0 &&
                payload.targetResponseRole.isNotBlank()) {
                payload
            } else {
                null
            }
        } catch (e: JsonSyntaxException) {
            null
        }
    }
}
