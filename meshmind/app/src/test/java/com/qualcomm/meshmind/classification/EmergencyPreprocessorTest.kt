package com.qualcomm.meshmind.classification

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStreamReader
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class EmergencyPreprocessorTest {

    data class Vector(
        val id: Int,
        val input: String,
        val expectedCleanText: String
    )

    @Test
    fun testPreprocessorAgainstGoldenVectors() {
        val inputStream = this.javaClass.classLoader?.getResourceAsStream("emergency_ai/preprocessing_golden_vectors.json")
            ?: throw AssertionError("Could not find preprocessing_golden_vectors.json")
            
        val jsonText = InputStreamReader(inputStream).readText()
        val type = object : TypeToken<List<Vector>>() {}.type
        val vectors: List<Vector> = Gson().fromJson(jsonText, type)
        
        var passed = 0
        var failed = 0
        
        for (item in vectors) {
            val actual = EmergencyPreprocessor.cleanText(item.input)
            
            if (item.expectedCleanText != actual) {
                failed++
                println("Mismatch on Vector ID: ${item.id}")
                println("Input:   '${escapeString(item.input)}'")
                println("Expected: '${escapeString(item.expectedCleanText)}'")
                println("Actual:   '${escapeString(actual)}'")
                throw AssertionError("Mismatch on Vector ID: ${item.id}")
            } else {
                passed++
            }
        }
        
        println("Generated vectors tested: ${vectors.size}")
        println("Passed: $passed")
        println("Failed: $failed")
    }
    
    private fun escapeString(str: String): String {
        return str.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
    }
}
