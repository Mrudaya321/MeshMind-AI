package com.qualcomm.meshmind.classification

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.OnnxJavaType
import com.qualcomm.meshmind.logging.MeshLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import kotlin.math.exp
import java.util.UUID

sealed class EmergencyClassificationResult {
    data class Classified(
        val classIndex: Int,
        val classLabel: String,
        val confidence: Double
    ) : EmergencyClassificationResult()
    data class Unavailable(
        val reason: String,
        val exceptionClass: String? = null
    ) : EmergencyClassificationResult()
}

enum class AiRuntimeState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    FAILED
}

class EmergencyClassifier(
    private val context: Context,
    private val sessionFactory: OrtSessionFactory = DefaultOrtSessionFactory()
) {

    companion object {
        private const val TAG = "EmergencyClassifier"
        private val LABEL_MAP = mapOf(
            0 to "Fire",
            1 to "Flood",
            2 to "Earthquake",
            3 to "Storm",
            4 to "Building Collapse",
            5 to "Medical Emergency",
            6 to "Security Threat",
            7 to "Chemical Explosion"
        )
    }

    interface OrtSessionFactory {
        fun createEnvironment(): OrtEnvironment
        fun createSession(env: OrtEnvironment, modelPath: String, options: OrtSession.SessionOptions): OrtSession
        fun createTensor(env: OrtEnvironment, data: LongBuffer, shape: LongArray): OnnxTensor
    }

    class DefaultOrtSessionFactory : OrtSessionFactory {
        override fun createEnvironment(): OrtEnvironment = OrtEnvironment.getEnvironment()
        override fun createSession(env: OrtEnvironment, modelPath: String, options: OrtSession.SessionOptions): OrtSession {
            return env.createSession(modelPath, options)
        }
        override fun createTensor(env: OrtEnvironment, data: LongBuffer, shape: LongArray): OnnxTensor {
            return OnnxTensor.createTensor(env, data, shape)
        }
    }

    private val initMutex = Mutex()
    
    @Volatile var runtimeState: AiRuntimeState = AiRuntimeState.UNINITIALIZED
        private set
    
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokenizer: EmergencyTokenizer? = null

    // Diagnostics
    @Volatile var isModelGraphStaged = false
    @Volatile var isExternalDataStaged = false
    @Volatile var tokenizerReady = false
    @Volatile var labelMapReady = false
    @Volatile var inputContractValidated = false
    @Volatile var outputContractValidated = false
    @Volatile var lastTraceId: String? = null
    @Volatile var lastClassificationStatus: String? = null
    @Volatile var lastPredictedClassIndex: Int? = null
    @Volatile var lastPredictedClassLabel: String? = null
    @Volatile var lastConfidence: Double? = null
    @Volatile var lastInferenceDurationMs: Long? = null
    @Volatile var lastAiFailureStage: String? = null
    @Volatile var lastAiFailureReason: String? = null

    suspend fun initialize() = withContext(Dispatchers.Default) {
        if (runtimeState == AiRuntimeState.READY) return@withContext
        if (runtimeState == AiRuntimeState.FAILED) return@withContext // Fast fail if permanently failed

        initMutex.withLock {
            if (runtimeState == AiRuntimeState.READY || runtimeState == AiRuntimeState.FAILED) return@withLock
            
            runtimeState = AiRuntimeState.INITIALIZING
            MeshLogger.i(TAG, "EMERGENCY_AI_INITIALIZATION_STARTED")

            try {
                // 1. Stage assets
                MeshLogger.i(TAG, "EMERGENCY_AI_MODEL_GRAPH_STAGING_STARTED")
                val installed = EmergencyModelAssetInstaller.installAssetsIfNeeded(context)
                if (!installed) {
                    failInitialization("AssetStaging", "Failed to stage model assets")
                    return@withLock
                }
                isModelGraphStaged = true
                isExternalDataStaged = true
                MeshLogger.i(TAG, "EMERGENCY_AI_MODEL_GRAPH_STAGED")
                MeshLogger.i(TAG, "EMERGENCY_AI_EXTERNAL_DATA_STAGED")

                // 2. Tokenizer & Label Map
                tokenizer = EmergencyTokenizer(context)
                tokenizerReady = true
                MeshLogger.i(TAG, "EMERGENCY_AI_TOKENIZER_READY")
                
                // Label map validation (ensure 8 classes map correctly)
                if (LABEL_MAP.size != 8) {
                    failInitialization("LabelMap", "Label map does not contain exactly 8 classes")
                    return@withLock
                }
                labelMapReady = true
                MeshLogger.i(TAG, "EMERGENCY_AI_LABEL_MAP_VALIDATED")

                // 3. ONNX Environment & Session
                MeshLogger.i(TAG, "EMERGENCY_AI_SESSION_CREATE_STARTED")
                ortEnv = sessionFactory.createEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                }
                val modelPath = EmergencyModelAssetInstaller.getModelPath(context)
                ortSession = sessionFactory.createSession(ortEnv!!, modelPath, sessionOptions)
                MeshLogger.i(TAG, "EMERGENCY_AI_SESSION_CREATED")

                // 4. Contract Validation
                val session = ortSession!!
                
                // Input Contract
                val inputInfo = session.inputInfo
                if (!inputInfo.containsKey("input_ids") || !inputInfo.containsKey("attention_mask")) {
                    failInitialization("ContractValidation", "Missing required inputs")
                    return@withLock
                }
                val inputIdsInfo = inputInfo["input_ids"]?.info as? TensorInfo
                val attnMaskInfo = inputInfo["attention_mask"]?.info as? TensorInfo
                if (inputIdsInfo?.type != OnnxJavaType.INT64 || attnMaskInfo?.type != OnnxJavaType.INT64) {
                    failInitialization("ContractValidation", "Inputs must be INT64")
                    return@withLock
                }
                inputContractValidated = true
                MeshLogger.i(TAG, "EMERGENCY_AI_INPUT_CONTRACT_VALIDATED")

                // Output Contract
                val outputInfo = session.outputInfo
                if (!outputInfo.containsKey("logits")) {
                    failInitialization("ContractValidation", "Missing logits output")
                    return@withLock
                }
                val logitsInfo = outputInfo["logits"]?.info as? TensorInfo
                if (logitsInfo?.type != OnnxJavaType.FLOAT) {
                    failInitialization("ContractValidation", "Logits must be FLOAT32")
                    return@withLock
                }
                // Check static class dimension if available
                val shape = logitsInfo.shape
                if (shape != null && shape.size >= 2) {
                    val classDim = shape[shape.size - 1]
                    if (classDim != -1L && classDim != 8L) {
                        failInitialization("ContractValidation", "Expected 8 classes, got $classDim")
                        return@withLock
                    }
                }
                outputContractValidated = true
                MeshLogger.i(TAG, "EMERGENCY_AI_OUTPUT_CONTRACT_VALIDATED")

                runtimeState = AiRuntimeState.READY
                MeshLogger.i(TAG, "EMERGENCY_AI_MODEL_READY")

            } catch (e: Exception) {
                failInitialization("InitializationException", e.message ?: "Unknown error")
            }
        }
    }

    private fun failInitialization(stage: String, reason: String) {
        lastAiFailureStage = stage
        lastAiFailureReason = reason
        runtimeState = AiRuntimeState.FAILED
        MeshLogger.e(TAG, "Initialization failed at $stage: $reason")
        
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {}
        ortSession = null
        ortSession = null
        ortEnv = null
    }

    suspend fun retryInitialization() = withContext(Dispatchers.Default) {
        initMutex.withLock {
            if (runtimeState == AiRuntimeState.FAILED) {
                runtimeState = AiRuntimeState.UNINITIALIZED
                lastAiFailureStage = null
                lastAiFailureReason = null
            }
        }
        initialize()
    }

    suspend fun classify(text: String): EmergencyClassificationResult = withContext(Dispatchers.Default) {
        val traceId = UUID.randomUUID().toString()
        lastTraceId = traceId

        if (runtimeState == AiRuntimeState.UNINITIALIZED) {
            initialize()
        }

        if (runtimeState != AiRuntimeState.READY) {
            lastClassificationStatus = "UNAVAILABLE"
            return@withContext EmergencyClassificationResult.Unavailable(lastAiFailureReason ?: "NOT_READY", lastAiFailureStage)
        }

        val session = ortSession ?: return@withContext EmergencyClassificationResult.Unavailable("ORT_SESSION_MISSING")
        val tok = tokenizer ?: return@withContext EmergencyClassificationResult.Unavailable("TOKENIZER_MISSING")

        val startTime = System.currentTimeMillis()
        MeshLogger.i(TAG, "EMERGENCY_CLASSIFICATION_STARTED [$traceId]")

        try {
            val cleanText = EmergencyPreprocessor.cleanText(text)
            MeshLogger.i(TAG, "EMERGENCY_PREPROCESSING_COMPLETED [$traceId]")

            /*
             * TRAINING-DISTRIBUTION LIMITATION DOCUMENTATION:
             * The authoritative Python training dataset excluded cleaned messages with fewer than 8 characters
             * (mapped_df["text_clean"].str.len() >= 8).
             * 
             * The Android inference runtime intentionally DOES NOT reproduce this as an inference gate because 
             * it was a dataset-quality filtering operation, rather than a model/runtime contract.
             * 
             * Short messages such as "Fire" and "Help" are therefore valid runtime inference inputs.
             * Their prediction quality may differ from longer in-distribution messages, but they are not 
             * silently suppressed, penalized, or overridden with hardcoded rules. The ONNX model remains 
             * the sole classification authority.
             */
            if (cleanText.isEmpty()) {
                MeshLogger.w(TAG, "EMERGENCY_CLASSIFICATION_UNAVAILABLE [$traceId]: EMPTY_AFTER_PREPROCESSING")
                return@withContext EmergencyClassificationResult.Unavailable("EMPTY_AFTER_PREPROCESSING")
            }
            val inputIds = tok.tokenize(cleanText)
            val attentionMask = tok.getAttentionMask(inputIds)
            MeshLogger.i(TAG, "EMERGENCY_TOKENIZATION_COMPLETED [$traceId]")

            val env = ortEnv ?: return@withContext EmergencyClassificationResult.Unavailable("ORT_ENVIRONMENT_MISSING")
            
            val shape = longArrayOf(1, EmergencyTokenizer.MAX_LENGTH.toLong())
            val inputIdsTensor = sessionFactory.createTensor(env, LongBuffer.wrap(inputIds), shape)
            val attentionMaskTensor = sessionFactory.createTensor(env, LongBuffer.wrap(attentionMask), shape)

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )

            MeshLogger.i(TAG, "EMERGENCY_ONNX_RUN_STARTED [$traceId]")
            val result = session.run(inputs)
            MeshLogger.i(TAG, "EMERGENCY_ONNX_RUN_COMPLETED [$traceId]")
            
            val logitsTensor = result.get("logits").get() as OnnxTensor
            val value = logitsTensor.value
            
            if (value !is Array<*> || value.isEmpty() || value[0] !is FloatArray) {
                MeshLogger.e(TAG, "EMERGENCY_AI_INVALID_OUTPUT_CONTRACT [$traceId]: Invalid logits shape or type")
                result.close()
                inputIdsTensor.close()
                attentionMaskTensor.close()
                return@withContext EmergencyClassificationResult.Unavailable("INVALID_LOGITS_TYPE")
            }

            val logitsArray = (value[0] as FloatArray)
            if (logitsArray.size != 8) {
                MeshLogger.e(TAG, "EMERGENCY_AI_INVALID_OUTPUT_CONTRACT [$traceId]: Expected 8 logits, got ${logitsArray.size}")
                result.close()
                inputIdsTensor.close()
                attentionMaskTensor.close()
                return@withContext EmergencyClassificationResult.Unavailable("INVALID_LOGITS_SHAPE")
            }

            var maxLogit = Float.NEGATIVE_INFINITY
            for (logit in logitsArray) {
                if (logit.isNaN() || logit.isInfinite()) {
                    MeshLogger.e(TAG, "EMERGENCY_AI_INVALID_OUTPUT_CONTRACT [$traceId]: Logit is NaN or Infinite")
                    result.close()
                    inputIdsTensor.close()
                    attentionMaskTensor.close()
                    return@withContext EmergencyClassificationResult.Unavailable("NON_FINITE_LOGITS")
                }
                if (logit > maxLogit) {
                    maxLogit = logit
                }
            }

            // Numerically stable Softmax
            var sumExp = 0.0
            val expArray = DoubleArray(8)
            for (i in 0 until 8) {
                expArray[i] = exp((logitsArray[i] - maxLogit).toDouble())
                sumExp += expArray[i]
            }
            
            if (sumExp <= 0.0 || sumExp.isNaN() || sumExp.isInfinite()) {
                MeshLogger.e(TAG, "EMERGENCY_AI_INVALID_OUTPUT_CONTRACT [$traceId]: Softmax sumExp invalid")
                result.close()
                inputIdsTensor.close()
                attentionMaskTensor.close()
                return@withContext EmergencyClassificationResult.Unavailable("INVALID_SOFTMAX_SUM")
            }
            
            var maxProb = -1.0
            var argmax = -1
            for (i in 0 until 8) {
                val prob = expArray[i] / sumExp
                if (prob > maxProb) {
                    maxProb = prob
                    argmax = i
                }
            }

            MeshLogger.i(TAG, "EMERGENCY_SOFTMAX_COMPLETED [$traceId]")

            inputIdsTensor.close()
            attentionMaskTensor.close()
            result.close()

            val label = LABEL_MAP[argmax] ?: "Unknown"
            
            lastInferenceDurationMs = System.currentTimeMillis() - startTime
            lastClassificationStatus = "SUCCESS"
            lastPredictedClassIndex = argmax
            lastPredictedClassLabel = label
            lastConfidence = maxProb
            MeshLogger.i(TAG, "EMERGENCY_CLASSIFICATION_COMPLETED [$traceId]")

            return@withContext EmergencyClassificationResult.Classified(
                classIndex = argmax,
                classLabel = label,
                confidence = maxProb
            )
        } catch (e: Exception) {
            MeshLogger.e(TAG, "EMERGENCY_CLASSIFICATION_UNAVAILABLE [$traceId]", e)
            lastClassificationStatus = "UNAVAILABLE"
            lastAiFailureStage = "Inference"
            lastAiFailureReason = e.message
            return@withContext EmergencyClassificationResult.Unavailable(e.message ?: "UNKNOWN_EXCEPTION", e.javaClass.simpleName)
        }
    }

    fun shutdown() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {}
        ortSession = null
        ortEnv = null
        runtimeState = AiRuntimeState.UNINITIALIZED
    }
}
