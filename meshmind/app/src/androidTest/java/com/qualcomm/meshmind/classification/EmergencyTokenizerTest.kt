package com.qualcomm.meshmind.classification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class EmergencyTokenizerTest {

    private lateinit var tokenizer: EmergencyTokenizer
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        EmergencyModelAssetInstaller.installAssetsIfNeeded(context)
        tokenizer = EmergencyTokenizer(context)
    }

    @Test
    fun testTokenizerAgainstGoldenVectors() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val inputStream = testContext.assets.open("emergency_ai/tokenizer_golden_vectors.json")
            
        val jsonText = InputStreamReader(inputStream).readText()
        val jsonArray = JSONArray(jsonText)
        
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            val input = item.getString("input")
            
            val expectedInputIdsJson = item.getJSONArray("input_ids")
            val expectedAttentionMaskJson = item.getJSONArray("attention_mask")
            
            val expectedInputIds = LongArray(64)
            val expectedAttentionMask = LongArray(64)
            
            assertEquals("Golden vector input_ids length must be exactly 64", 64, expectedInputIdsJson.length())
            assertEquals("Golden vector attention_mask length must be exactly 64", 64, expectedAttentionMaskJson.length())
            
            for (j in 0 until 64) {
                expectedInputIds[j] = expectedInputIdsJson.getLong(j)
                expectedAttentionMask[j] = expectedAttentionMaskJson.getLong(j)
            }
            
            val actualInputIds = tokenizer.tokenize(input)
            val actualAttentionMask = tokenizer.getAttentionMask(actualInputIds)
            
            assertEquals("Actual input_ids length must be exactly 64", 64, actualInputIds.size)
            assertEquals("Actual attention_mask length must be exactly 64", 64, actualAttentionMask.size)
            
            for (j in 0 until 64) {
                if (expectedInputIds[j] != actualInputIds[j]) {
                    throw AssertionError("Mismatch at index $j for input '$input'. Expected ${expectedInputIds[j]}, actual ${actualInputIds[j]}")
                }
                if (expectedAttentionMask[j] != actualAttentionMask[j]) {
                    throw AssertionError("Attention mask mismatch at index $j for input '$input'. Expected ${expectedAttentionMask[j]}, actual ${actualAttentionMask[j]}")
                }
            }
        }
    }
}
