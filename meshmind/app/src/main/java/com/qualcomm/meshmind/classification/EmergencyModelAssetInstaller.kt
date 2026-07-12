package com.qualcomm.meshmind.classification

import android.content.Context
import android.content.res.AssetManager
import com.qualcomm.meshmind.logging.MeshLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object EmergencyModelAssetInstaller {
    private const val TAG = "AssetInstaller"
    private const val ASSET_DIR = "emergency_ai"

    val MODEL_FILE_NAME = "meshmind_classifier.onnx"
    val MODEL_DATA_NAME = "meshmind_classifier.onnx.data"
    val VOCAB_FILE_NAME = "vocab.txt"
    val LABEL_MAP_NAME = "label_map.json"

    fun installAssetsIfNeeded(context: Context): Boolean {
        val targetDir = File(context.filesDir, ASSET_DIR)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val requiredFiles = listOf(MODEL_FILE_NAME, MODEL_DATA_NAME, VOCAB_FILE_NAME, LABEL_MAP_NAME)
        var allInstalled = true

        for (fileName in requiredFiles) {
            val outFile = File(targetDir, fileName)
            val assetPath = "$ASSET_DIR/$fileName"
            
            try {
                // Get authoritative length. If compressed, assetFd.length may be UNKNOWN_LENGTH, but openFd() throws FileNotFoundException!
                var authoritativeLength: Long = -1L
                try {
                    context.assets.openFd(assetPath).use { fd ->
                        authoritativeLength = fd.length
                    }
                } catch (e: Exception) {
                    MeshLogger.d(TAG, "Asset $fileName could not be opened with openFd (likely compressed). Falling back to predefined sizes.")
                    authoritativeLength = when (fileName) {
                        MODEL_FILE_NAME -> 782193L
                        MODEL_DATA_NAME -> 90594304L
                        VOCAB_FILE_NAME -> 231508L
                        LABEL_MAP_NAME -> 693L
                        else -> -1L
                    }
                }

                if (outFile.exists() && outFile.length() == authoritativeLength) {
                    MeshLogger.d(TAG, "Asset $fileName already exists and length matches ($authoritativeLength bytes). Skipping.")
                    continue
                }

                MeshLogger.i(TAG, "Installing asset $fileName to ${outFile.absolutePath} (Expected: $authoritativeLength bytes)")
                
                if (!copyAssetAtomically(context, assetPath, targetDir, fileName, authoritativeLength)) {
                    MeshLogger.e(TAG, "Failed to copy asset atomically: $fileName")
                    allInstalled = false
                }
            } catch (e: Exception) {
                MeshLogger.e(TAG, "Failed to inspect or install asset $fileName", e)
                allInstalled = false
            }
        }
        return allInstalled
    }

    private fun copyAssetAtomically(context: Context, assetPath: String, targetDir: File, fileName: String, expectedLength: Long): Boolean {
        val tmpFile = File(targetDir, "$fileName.tmp")
        try {
            var bytesCopied = 0L
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tmpFile).use { fos ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                        bytesCopied += read
                    }
                    fos.flush()
                    fos.fd.sync() // Ensure durable write
                }
            }

            // Fallback for compressed assets where expectedLength was UNKNOWN_LENGTH (-1L)
            val targetLength = if (expectedLength == android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH) bytesCopied else expectedLength

            if (tmpFile.length() != targetLength) {
                MeshLogger.e(TAG, "Length mismatch for $fileName. Expected $targetLength, got ${tmpFile.length()}")
                tmpFile.delete()
                return false
            }

            if (tmpFile.length() == 0L) {
                MeshLogger.e(TAG, "Zero length for $fileName. Rejecting.")
                tmpFile.delete()
                return false
            }

            val outFile = File(targetDir, fileName)
            // Rename is atomic on POSIX if on same filesystem
            if (!tmpFile.renameTo(outFile)) {
                MeshLogger.e(TAG, "Failed to rename tmp file for $fileName")
                tmpFile.delete()
                return false
            }

            return true
        } catch (e: IOException) {
            MeshLogger.e(TAG, "Exception copying $assetPath to tmp", e)
            tmpFile.delete()
            return false
        }
    }

    fun getModelPath(context: Context): String {
        return File(context.filesDir, "$ASSET_DIR/$MODEL_FILE_NAME").absolutePath
    }

    fun getVocabPath(context: Context): String {
        return File(context.filesDir, "$ASSET_DIR/$VOCAB_FILE_NAME").absolutePath
    }
}
