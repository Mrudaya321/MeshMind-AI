package com.qualcomm.meshmind.classification

import java.text.Normalizer
import java.util.regex.Pattern

object EmergencyPreprocessor {

    // Authoritative Python regexes
    private val URL_RE = Regex("https?://\\S+|www\\.\\S+")
    private val USER_RE = Regex("@\\w+")
    private val HTML_ENTITY_RE = Regex("&[a-zA-Z]+;|&#\\d+;")
    private val WS_RE = Regex("\\s+")
    
    // [^\x00-\x7F]+ equivalent in Kotlin
    private val NON_ASCII_RE = Regex("[^\\x00-\\x7F]+")
    
    // #(\w+)
    private val HASHTAG_RE = Regex("#(\\w+)")
    
    // \brt\b case-insensitive
    private val RT_RE = Regex("\\brt\\b", RegexOption.IGNORE_CASE)

    fun cleanText(input: String): String {
        // 1. NFKC Normalization
        var t = Normalizer.normalize(input, Normalizer.Form.NFKC)

        // 2. Remove URLs
        t = URL_RE.replace(t, " ")
        
        // 3. Remove HTML entities
        t = HTML_ENTITY_RE.replace(t, " ")
        
        // 4. Remove Usernames
        t = USER_RE.replace(t, " ")

        // 5. Remove all non-ASCII characters
        t = NON_ASCII_RE.replace(t, " ")

        // 6. Remove hashtag symbol but keep content
        t = HASHTAG_RE.replace(t, "$1")

        // 7. Remove isolated "rt" (case-insensitive)
        t = RT_RE.replace(t, " ")

        // 8. Collapse whitespace and trim
        t = WS_RE.replace(t, " ").trim()

        return t
    }
}
