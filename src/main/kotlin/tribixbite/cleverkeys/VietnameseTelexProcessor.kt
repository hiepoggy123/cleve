package tribixbite.cleverkeys

/**
 * Basic Vietnamese Telex processor.
 * Intercepts keystrokes and applies Telex rules to the current word.
 */
object VietnameseTelexProcessor {

    data class TelexResult(val newWord: String, val charsToDelete: Int)

    // Tone map: 0=ngang, 1=sắc, 2=huyền, 3=hỏi, 4=ngã, 5=nặng
    private val VOWEL_TONES = mapOf(
        'a' to charArrayOf('a', 'á', 'à', 'ả', 'ã', 'ạ'),
        'ă' to charArrayOf('ă', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ'),
        'â' to charArrayOf('â', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ'),
        'e' to charArrayOf('e', 'é', 'è', 'ẻ', 'ẽ', 'ẹ'),
        'ê' to charArrayOf('ê', 'ế', 'ề', 'ể', 'ễ', 'ệ'),
        'i' to charArrayOf('i', 'í', 'ì', 'ỉ', 'ĩ', 'ị'),
        'o' to charArrayOf('o', 'ó', 'ò', 'ỏ', 'õ', 'ọ'),
        'ô' to charArrayOf('ô', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ'),
        'ơ' to charArrayOf('ơ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ'),
        'u' to charArrayOf('u', 'ú', 'ù', 'ủ', 'ũ', 'ụ'),
        'ư' to charArrayOf('ư', 'ứ', 'ừ', 'ử', 'ữ', 'ự'),
        'y' to charArrayOf('y', 'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ')
    )

    private val TONE_CHARS = mapOf('s' to 1, 'f' to 2, 'r' to 3, 'x' to 4, 'j' to 5, 'z' to 0)

    // Reverse map to find base vowel and current tone
    private val CHAR_TO_BASE_TONE = mutableMapOf<Char, Pair<Char, Int>>()

    init {
        VOWEL_TONES.forEach { (base, array) ->
            array.forEachIndexed { toneIndex, c ->
                CHAR_TO_BASE_TONE[c] = Pair(base, toneIndex)
                // Also uppercase
                CHAR_TO_BASE_TONE[c.uppercaseChar()] = Pair(base.uppercaseChar(), toneIndex)
            }
        }
    }

    /**
     * Process the text before the cursor and the newly typed character.
     * Returns a TelexResult if a rule was applied, otherwise null.
     */
    fun processTelex(textBeforeCursor: String, newChar: Char): TelexResult? {
        val wordRegex = Regex("([a-zA-ZÀ-ỹ]+)$")
        val match = wordRegex.find(textBeforeCursor)
        
        // If there's no word before cursor, we only handle 'dd' etc if typed directly
        val word = match?.value ?: ""
        
        if (word.isEmpty()) {
            return applyBasicCharRule(newChar.toString(), newChar, 0)
        }

        // 1. Try applying tone
        val lowerNewChar = newChar.lowercaseChar()
        if (TONE_CHARS.containsKey(lowerNewChar)) {
            val (toneAppliedWord, canceled) = applyTone(word, TONE_CHARS[lowerNewChar]!!)
            if (toneAppliedWord != word || canceled) {
                // If it was canceled (e.g. typing 's' when word already has 's'), we append the new character!
                val finalWord = if (canceled) toneAppliedWord + newChar else toneAppliedWord
                return TelexResult(finalWord, word.length)
            }
        }

        // 2. Try applying basic char rules (aa, aw, etc.) at the end of the word
        return applyBasicCharRule(word + newChar, newChar, word.length)
    }

    private fun applyBasicCharRule(wordWithNewChar: String, newChar: Char, originalWordLen: Int): TelexResult? {
        if (wordWithNewChar.length < 2) return null
        
        val lastTwo = wordWithNewChar.takeLast(2).lowercase()
        val isUpper = wordWithNewChar[wordWithNewChar.length - 2].isUpperCase()
        val isLastUpper = wordWithNewChar[wordWithNewChar.length - 1].isUpperCase()

        val replacement = when (lastTwo) {
            "aa" -> "â"
            "âa" -> "aa"
            "aw" -> "ă"
            "ăw" -> "aw"
            "ee" -> "ê"
            "êe" -> "ee"
            "oo" -> "ô"
            "ôo" -> "oo"
            "ow" -> "ơ"
            "ơw" -> "ow"
            "uw" -> "ư"
            "ưw" -> "uw"
            "dd" -> "đ"
            "đd" -> "dd"
            "w" -> if (wordWithNewChar.length > 1 && isBaseVowel(wordWithNewChar[wordWithNewChar.length - 2])) {
                // simple 'w' rule for ư/ơ if preceded by u/o
                val prev = wordWithNewChar[wordWithNewChar.length - 2].lowercaseChar()
                if (prev == 'u') "ư" else if (prev == 'o') "ơ" else null
            } else null
            else -> null
        }

        if (replacement != null) {
            val finalChar = if (isUpper) {
                if (isLastUpper) replacement.uppercase()
                else if (replacement.length > 1) replacement.replaceFirstChar { it.uppercase() }
                else replacement.uppercase()
            } else replacement
            // We replace the last character of the original word + the new character
            // Since new character hasn't been committed yet, we delete 1 character (the last of the original word)
            // and commit the replacement.
            val newWord = wordWithNewChar.dropLast(2) + finalChar
            return TelexResult(newWord, originalWordLen)
        }

        return null
    }

    private fun isBaseVowel(c: Char): Boolean = CHAR_TO_BASE_TONE.containsKey(c)

    private fun applyTone(word: String, newTone: Int): Pair<String, Boolean> {
        // Find the main vowel to apply tone
        // Heuristic: right-most vowel, but if it's "qu" + vowel, apply to vowel.
        // If there's a vowel cluster (e.g. "oa"), apply to the second one if it ends the word, else the first.
        // For simplicity, we just find the first vowel from the right that is not 'u' preceded by 'q'.
        
        var vowelIndex = -1
        for (i in word.indices.reversed()) {
            val c = word[i]
            if (CHAR_TO_BASE_TONE.containsKey(c)) {
                // check for 'qu'
                if (c.lowercaseChar() == 'u' && i > 0 && word[i-1].lowercaseChar() == 'q') {
                    continue // 'u' in 'qu' doesn't take the tone usually
                }
                vowelIndex = i
                break
            }
        }

        if (vowelIndex != -1) {
            val c = word[vowelIndex]
            val info = CHAR_TO_BASE_TONE[c]!!
            val baseVowel = info.first
            val currentTone = info.second
            
            // If the word already has this tone, "z" removes it, other tones override
            val canceled = (newTone != 0 && currentTone == newTone)
            val targetTone = if (newTone == 0) 0 else if (canceled) 0 else newTone
            
            val newVowel = if (baseVowel.isUpperCase()) {
                VOWEL_TONES[baseVowel.lowercaseChar()]!![targetTone].uppercaseChar()
            } else {
                VOWEL_TONES[baseVowel]!![targetTone]
            }
            
            val newWord = word.substring(0, vowelIndex) + newVowel + word.substring(vowelIndex + 1)
            return Pair(newWord, canceled)
        }
        
        return Pair(word, false)
    }
}
