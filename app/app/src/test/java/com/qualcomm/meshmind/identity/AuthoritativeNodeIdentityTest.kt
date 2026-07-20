package com.qualcomm.meshmind.identity

import com.qualcomm.meshmind.viewmodel.ChatViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class AuthoritativeNodeIdentityTest {

    @Test
    fun `node_local_user is not hardcoded in ReliableCommunicationManager or ChatViewModel`() {
        val projectDir = System.getProperty("user.dir")
        val rcmFile = File(projectDir, "src/main/java/com/qualcomm/meshmind/communication/ReliableCommunicationManager.kt")
        val cvmFile = File(projectDir, "src/main/java/com/qualcomm/meshmind/viewmodel/ChatViewModel.kt")
        
        if (rcmFile.exists()) {
            val rcmContent = rcmFile.readText()
            assertFalse("ReliableCommunicationManager must not contain hardcoded node_local_user", rcmContent.contains("\"node_local_user\""))
        }
        
        if (cvmFile.exists()) {
            val cvmContent = cvmFile.readText()
            assertFalse("ChatViewModel must not contain hardcoded node_local_user", cvmContent.contains("\"node_local_user\""))
        }
    }
}
