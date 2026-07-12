package com.qualcomm.meshmind.classification

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.InputStreamReader

@RunWith(JUnit4::class)
class EmergencyPipelineParityTest {

    data class PipelineVector(
        val id: Int,
        val input: String,
        val expectedCleanText: String,
        val expectedInputIds: List<Long>?,
        val expectedAttentionMask: List<Long>?
    )

    @Test
    fun testCombinedPipelineParity() {
        val inputStream = this.javaClass.classLoader?.getResourceAsStream("emergency_ai/preprocessing_golden_vectors.json")
            ?: throw AssertionError("Could not find preprocessing_golden_vectors.json")
            
        val jsonText = InputStreamReader(inputStream).readText()
        val type = object : TypeToken<List<PipelineVector>>() {}.type
        val vectors: List<PipelineVector> = Gson().fromJson(jsonText, type)
        
        // This test only verifies that preprocessing output matches, because testing tokenizer requires Context in standard Android tests,
        // which we avoid in standard JVM tests unless Mockito is used. But we can just use the provided vectors to ensure clean_text matches.
        
        var passed = 0
        for (item in vectors) {
            val kotlinClean = EmergencyPreprocessor.cleanText(item.input)
            assertEquals("Cleaned text mismatch at ID ${item.id}", item.expectedCleanText, kotlinClean)
            passed++
        }
        println("Pipeline preprocessing parts validated: $passed")
    }
}
