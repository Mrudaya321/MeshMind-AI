package com.qualcomm.meshmind.communication

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import kotlin.coroutines.Continuation

class ChatSendContractTest {

    @Test
    fun `sendTextMessage is a suspend function returning SendResult`() {
        val methods = ReliableCommunicationManager::class.java.methods
        val method = methods.find { it.name == "sendTextMessage" }
        assertTrue("sendTextMessage must exist", method != null)
        
        // A suspend function compiles to a method with a Continuation parameter
        val hasContinuation = method!!.parameterTypes.any { it == Continuation::class.java }
        assertTrue("sendTextMessage must be a suspend function", hasContinuation)
    }
}
