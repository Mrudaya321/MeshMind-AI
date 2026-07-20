package com.qualcomm.meshmind.performance

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerformanceTest {

    private lateinit var performanceManager: PerformanceManager

    @Before
    fun setUp() {
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext() = this
        }
        performanceManager = PerformanceManager.getInstance(mockContext)
        ByteBufferPool.getInstance().clear()
    }

    @Test
    fun testByteBufferPoolRecycling() {
        val pool = ByteBufferPool.getInstance()
        
        // 1. Acquire small buffer
        val buf1 = pool.acquire(100)
        assertNotNull(buf1)
        assertEquals(512, buf1.size)

        // Modify content
        buf1[0] = 5
        
        // 2. Release buffer back to pool
        pool.release(buf1)
        
        // Verify buffer got cleared on release
        assertEquals(0, buf1[0].toInt())

        // 3. Re-acquire, should pull same preallocated array
        val buf2 = pool.acquire(100)
        assertNotNull(buf2)
        assertEquals(512, buf2.size)
    }

    @Test
    fun testBatteryOptimizationScaling() {
        // Under 20% battery -> scale factor 0.2
        assertEquals(0.2, performanceManager.getBatteryOptimizationScalingFactor(15), 0.01)
        
        // Under 50% battery -> scale factor 0.5
        assertEquals(0.5, performanceManager.getBatteryOptimizationScalingFactor(40), 0.01)
        
        // Full battery -> scale factor 1.0
        assertEquals(1.0, performanceManager.getBatteryOptimizationScalingFactor(80), 0.01)
    }
}
