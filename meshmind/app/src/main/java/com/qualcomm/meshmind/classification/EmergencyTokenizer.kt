package com.qualcomm.meshmind.classification

import android.content.Context
import com.qualcomm.meshmind.logging.MeshLogger
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.text.Normalizer
import kotlin.math.min

class EmergencyTokenizer(context: Context) {
    private val vocab = mutableMapOf<String, Int>()
    
    companion object {
        private const val TAG = "EmergencyTokenizer"
        const val PAD_ID = 0
        const val UNK_ID = 100
        const val CLS_ID = 101
        const val SEP_ID = 102
        const val MAX_LENGTH = 64
        const val MAX_CHAR = 100
    }

    init {
        val vocabFile = File(EmergencyModelAssetInstaller.getVocabPath(context))
        if (vocabFile.exists()) {
            BufferedReader(FileReader(vocabFile)).use { reader ->
                var id = 0
                reader.forEachLine { line ->
                    val token = line.trim()
                    // Allow empty strings since vocabulary relies on line number as index
                    vocab[token] = id
                    id++
                }
            }
        } else {
            MeshLogger.e(TAG, "Vocab file missing at ${vocabFile.absolutePath}")
        }
    }

    private fun isWhitespace(cp: Int): Boolean {
        if (cp == 0x0020 || cp == 0x0009 || cp == 0x000A || cp == 0x000D) return true
        return Character.isWhitespace(cp)
    }

    private fun isControl(cp: Int): Boolean {
        if (cp == 0x0009 || cp == 0x000A || cp == 0x000D) return false
        val type = Character.getType(cp).toByte()
        return type == Character.CONTROL || type == Character.FORMAT
    }

    private fun isPunctuation(cp: Int): Boolean {
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        val type = Character.getType(cp).toByte()
        return type == Character.CONNECTOR_PUNCTUATION ||
               type == Character.DASH_PUNCTUATION ||
               type == Character.END_PUNCTUATION ||
               type == Character.INITIAL_QUOTE_PUNCTUATION ||
               type == Character.FINAL_QUOTE_PUNCTUATION ||
               type == Character.OTHER_PUNCTUATION ||
               type == Character.START_PUNCTUATION
    }

    private fun isChineseChar(cp: Int): Boolean {
        return (cp in 0x4E00..0x9FFF) ||
               (cp in 0x3400..0x4DBF) ||
               (cp in 0x20000..0x2A6DF) ||
               (cp in 0x2A700..0x2B73F) ||
               (cp in 0x2B740..0x2B81F) ||
               (cp in 0x2B820..0x2CEAF) ||
               (cp in 0xF900..0xFAFF) ||
               (cp in 0x2F800..0x2FA1F)
    }

    private fun stripAccents(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = java.lang.StringBuilder()
        for (i in 0 until normalized.length) {
            val c = normalized[i]
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun cleanText(text: String): String {
        val sb = java.lang.StringBuilder()
        val len = text.length
        var i = 0
        while (i < len) {
            val cp = text.codePointAt(i)
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) {
                // skip
            } else if (isWhitespace(cp)) {
                sb.append(" ")
            } else {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun handleChineseChars(text: String): String {
        val sb = java.lang.StringBuilder()
        val len = text.length
        var i = 0
        while (i < len) {
            val cp = text.codePointAt(i)
            if (isChineseChar(cp)) {
                sb.append(" ")
                sb.appendCodePoint(cp)
                sb.append(" ")
            } else {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun basicTokenize(text: String): List<String> {
        // 1. clean_text
        var cleaned = cleanText(text)
        // 2. handle_chinese_chars
        cleaned = handleChineseChars(cleaned)
        // 3. lowercase and strip_accents
        cleaned = stripAccents(cleaned.lowercase())

        val tokens = mutableListOf<String>()
        val currentToken = java.lang.StringBuilder()

        val len = cleaned.length
        var i = 0
        while (i < len) {
            val cp = cleaned.codePointAt(i)
            if (isWhitespace(cp)) {
                if (currentToken.isNotEmpty()) {
                    tokens.add(currentToken.toString())
                    currentToken.setLength(0)
                }
            } else if (isPunctuation(cp)) {
                if (currentToken.isNotEmpty()) {
                    tokens.add(currentToken.toString())
                    currentToken.setLength(0)
                }
                tokens.add(String(Character.toChars(cp)))
            } else {
                currentToken.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        if (currentToken.isNotEmpty()) {
            tokens.add(currentToken.toString())
        }
        return tokens
    }

    private fun wordPieceTokenize(token: String): List<Int> {
        val subTokens = mutableListOf<Int>()
        var start = 0
        
        // Count characters (code points), not string length
        val cpCount = token.codePointCount(0, token.length)
        if (cpCount > MAX_CHAR) {
            subTokens.add(UNK_ID)
            return subTokens
        }
        
        var isBad = false
        while (start < token.length) {
            var end = token.length
            var curSubTokenId: Int? = null
            
            while (start < end) {
                var substr = token.substring(start, end)
                if (start > 0) {
                    substr = "##$substr"
                }
                if (vocab.containsKey(substr)) {
                    curSubTokenId = vocab[substr]
                    break
                }
                end -= Character.charCount(token.codePointBefore(end))
            }
            if (curSubTokenId == null) {
                isBad = true
                break
            }
            subTokens.add(curSubTokenId)
            start = end
        }
        
        return if (isBad) listOf(UNK_ID) else subTokens
    }

    fun tokenize(text: String): LongArray {
        val basicTokens = basicTokenize(text)
        val wordPieceTokens = mutableListOf<Int>()
        
        for (token in basicTokens) {
            val wps = wordPieceTokenize(token)
            for (wp in wps) {
                wordPieceTokens.add(wp)
            }
        }
        
        // Truncate to 62 tokens
        val truncatedLength = min(wordPieceTokens.size, MAX_LENGTH - 2)
        val truncatedTokens = wordPieceTokens.subList(0, truncatedLength)
        
        val result = LongArray(MAX_LENGTH)
        
        // Special Tokens + Padding
        result[0] = CLS_ID.toLong()
        for (i in 0 until truncatedTokens.size) {
            result[i + 1] = truncatedTokens[i].toLong()
        }
        val sepIndex = truncatedTokens.size + 1
        result[sepIndex] = SEP_ID.toLong()
        
        for (i in (sepIndex + 1) until MAX_LENGTH) {
            result[i] = PAD_ID.toLong()
        }
        return result
    }

    fun getAttentionMask(inputIds: LongArray): LongArray {
        val mask = LongArray(MAX_LENGTH)
        for (i in 0 until MAX_LENGTH) {
            mask[i] = if (inputIds[i] != PAD_ID.toLong()) 1L else 0L
        }
        return mask
    }
}
