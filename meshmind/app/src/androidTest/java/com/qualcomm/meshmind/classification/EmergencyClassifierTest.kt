package com.qualcomm.meshmind.classification

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@RunWith(AndroidJUnit4::class)
class EmergencyClassifierTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        EmergencyModelAssetInstaller.installAssetsIfNeeded(context)
    }

    class CountingSessionFactory(private val realEnv: OrtEnvironment) : EmergencyClassifier.OrtSessionFactory {
        var createSessionCallCount = 0
            private set

        override fun createEnvironment(): OrtEnvironment = realEnv

        override fun createSession(env: OrtEnvironment, modelPath: String, options: OrtSession.SessionOptions): OrtSession {
            createSessionCallCount++
            return env.createSession(modelPath, options)
        }
    }

    @Test
    fun testConcurrentInitializationCreatesSingleSession() = runBlocking {
        val env = OrtEnvironment.getEnvironment()
        val factory = CountingSessionFactory(env)
        val classifier = EmergencyClassifier(context, factory)

        coroutineScope {
            val jobs = (1..50).map {
                async {
                    classifier.initialize()
                }
            }
            jobs.awaitAll()
        }

        assertEquals("Session creation should be invoked exactly once", 1, factory.createSessionCallCount)
        assertEquals(AiRuntimeState.READY, classifier.runtimeState)
    }

    @Test
    fun testReadyNotPublishedBeforeContractValidation() = runBlocking {
        val env = OrtEnvironment.getEnvironment()
        val factory = CountingSessionFactory(env)
        val classifier = EmergencyClassifier(context, factory)

        assertEquals(AiRuntimeState.UNINITIALIZED, classifier.runtimeState)
        classifier.initialize()

        assertTrue(classifier.isModelGraphStaged)
        assertTrue(classifier.isExternalDataStaged)
        assertTrue(classifier.tokenizerReady)
        assertTrue(classifier.labelMapReady)
        assertTrue(classifier.inputContractValidated)
        assertTrue(classifier.outputContractValidated)
        assertEquals(AiRuntimeState.READY, classifier.runtimeState)
    }
}
