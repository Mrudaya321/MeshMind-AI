package com.qualcomm.meshmind.services

import org.junit.Assert.assertNotNull
import org.junit.Test

class MeshRuntimeServiceTest {

    @Test
    fun testServiceInstantiates() {
        val service = MeshRuntimeService()
        assertNotNull(service)
    }
}
