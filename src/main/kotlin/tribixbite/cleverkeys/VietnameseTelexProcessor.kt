package tribixbite.cleverkeys

import java.text.Normalizer

/**
 * Advanced Vietnamese Telex processor ported from ViKey's AlgorithmicTelex.
 * Provides syllable parsing, tone placement rules, and English fallback.
 */
object VietnameseTelexProcessor {

    data class TelexResult(val newWord: String, val charsToDelete: Int)

    // ── Character classification ──────────────────────────────────

    private val toneKeys = setOf('s', 'f', 'r', 'x', 'j')

    private val baseVowels = setOf(
        'a', 'ă', 'â', 'e', 'ê', 'i',
        'o', 'ô', 'ơ', 'u', 'ư', 'y',
    )

    private val consonantLetters = setOf(
        'b', 'c', 'd', 'đ', 'g', 'h', 'k', 'l', 'm', 'n',
        'p', 'q', 'r', 's', 't', 'v', 'x',
    )

    // ── Vietnamese onset consonants (longest first) ───────────────

    private val knownOnsets = listOf(
        "ngh", "ng", "ch", "gh", "gi", "kh", "nh", "ph", "th", "tr", "qu",
        "b", "c", "d", "đ", "g", "h", "k", "l", "m", "n",
        "p", "r", "s", "t", "v", "x",
    )

    // ── Vietnamese coda consonants ────────────────────────────────

    private val knownCodas = listOf(
        "ch", "ng", "nh", "c", "m", "n", "p", "t",
    )

    private val semivowelCodas = setOf('u', 'i', 'y', 'o')

    // ── Telex shortcut maps (Removed, using distant modifier algorithm) ───────────

    // ── Tone maps ─────────────────────────────────────────────────

    private val toneMaps = mapOf(
        's' to mapOf(
            'a' to 'á', 'ă' to 'ắ', 'â' to 'ấ',
            'e' to 'é', 'ê' to 'ế',
            'i' to 'í',
            'o' to 'ó', 'ô' to 'ố', 'ơ' to 'ớ',
            'u' to 'ú', 'ư' to 'ứ', 'y' to 'ý',
        ),
        'f' to mapOf(
            'a' to 'à', 'ă' to 'ằ', 'â' to 'ầ',
            'e' to 'è', 'ê' to 'ề',
            'i' to 'ì',
            'o' to 'ò', 'ô' to 'ồ', 'ơ' to 'ờ',
            'u' to 'ù', 'ư' to 'ừ', 'y' to 'ỳ',
        ),
        'r' to mapOf(
            'a' to 'ả', 'ă' to 'ẳ', 'â' to 'ẩ',
            'e' to 'ẻ', 'ê' to 'ể',
            'i' to 'ỉ',
            'o' to 'ỏ', 'ô' to 'ổ', 'ơ' to 'ở',
            'u' to 'ủ', 'ư' to 'ử', 'y' to 'ỷ',
        ),
        'x' to mapOf(
            'a' to 'ã', 'ă' to 'ẵ', 'â' to 'ẫ',
            'e' to 'ẽ', 'ê' to 'ễ',
            'i' to 'ĩ',
            'o' to 'õ', 'ô' to 'ỗ', 'ơ' to 'ỡ',
            'u' to 'ũ', 'ư' to 'ữ', 'y' to 'ỹ',
        ),
        'j' to mapOf(
            'a' to 'ạ', 'ă' to 'ặ', 'â' to 'ậ',
            'e' to 'ẹ', 'ê' to 'ệ',
            'i' to 'ị',
            'o' to 'ọ', 'ô' to 'ộ', 'ơ' to 'ợ',
            'u' to 'ụ', 'ư' to 'ự', 'y' to 'ỵ',
        ),
    )

    // ── Vietnamese orthographic tone placement rules ──────────────

    private val toneRules = mapOf(
        "oa" to 'a', "oe" to 'e', "uy" to 'y',
        "ưa" to 'ư', "ươ" to 'ơ', "uô" to 'ô',
        "ua" to 'u', "iê" to 'ê', "yê" to 'ê',
        "uyê" to 'ê', "uya" to 'y', "uye" to 'y',
        "uôi" to 'ô', "ươi" to 'ơ', "ươu" to 'ơ',
        "oai" to 'a', "oay" to 'a', "uay" to 'a',
        "oeo" to 'e', "oeu" to 'e',
        "ia" to 'i', "ya" to 'y',
        "iêu" to 'ê', "yêu" to 'ê',
        "ai" to 'a', "ay" to 'a', "au" to 'a', "ao" to 'a',
        "oi" to 'o', "ôi" to 'ô', "ơi" to 'ơ',
        "ui" to 'u', "ưi" to 'ư',
        "eo" to 'e', "êu" to 'ê',
        "iu" to 'i', "ưu" to 'ư',
        "ây" to 'â',
    )

    // ── IBus-Bamboo Phonetic Matrix (Ma trận âm ngữ) ──────────────

    private val firstConsonantSeqs = listOf(
        "b d đ g gh m n nh p ph r s t tr v z",
        "c h k kh qu th",
        "ch gi l ng ngh x",
        "đ l",
        "h",
    )

    private val vowelSeqs = listOf(
        "ê i ua uê uy y",
        "a iê oa uyê yê",
        "â ă e o oo ô ơ oe u ư uâ uô ươ",
        "oă",
        "uơ",
        "ai ao au âu ay ây eo êu ia iêu iu oai oao oay oeo oi ôi ơi ưa uây ui ưi uôi ươi ươu ưu uya uyu yêu",
        "ă",
        "i",
    )

    private val lastConsonantSeqs = listOf(
        "ch nh",
        "c ng",
        "m n p t",
        "k",
        "c",
    )

    private val cvMatrix = listOf(
        listOf(0, 1, 2, 5),
        listOf(0, 1, 2, 3, 4, 5),
        listOf(0, 1, 2, 3, 5),
        listOf(6),
        listOf(7),
    )

    private val vcMatrix = listOf(
        listOf(0, 2),
        listOf(0, 1, 2),
        listOf(1, 2),
        listOf(1, 2),
        emptyList(),
        emptyList(),
        listOf(3),
        listOf(4),
    )

    // ──────────────────────────────────────────────────────────────
    //  Syllable model
    // ──────────────────────────────────────────────────────────────

    private data class Syllable(
        val onset: String = "",
        val nucleus: String = "",
        val coda: String = "",
    )

    // ──────────────────────────────────────────────────────────────
    //  Public API for CleverKeys
    // ──────────────────────────────────────────────────────────────

    /**
     * Replaces getActions. Returns TelexResult or null if no Telex rule applied (simple append).
     */
    fun processTelex(textBeforeCursor: String, newChar: Char): TelexResult? {
        val normalized = Normalizer.normalize(textBeforeCursor, Normalizer.Form.NFC)
        
        if (normalized.isEmpty()) {
            val first = firstChar(newChar)
            return if (first == newChar.toString()) null else TelexResult(first, 0)
        }
        
        if (!normalized.last().isLetter()) {
            return null // Just append normally
        }

        if (newChar.lowercaseChar() == 'z') {
            return handleCancel(normalized)
        }

        val word = lastWord(normalized)
        if (word.isEmpty()) {
            val first = firstChar(newChar)
            return if (first == newChar.toString()) null else TelexResult(first, 0)
        }

        val (charsToDelete, newWord) = processWord(word, newChar)
        
        // If the result is just appending the character, return null so CleverKeys handles normally
        if (charsToDelete == word.length && newWord == word + newChar) {
            return null
        }
        
        return TelexResult(newWord, charsToDelete)
    }

    // ──────────────────────────────────────────────────────────────
    //  First character in a new word
    // ──────────────────────────────────────────────────────────────

    private fun firstChar(ch: Char): String {
        return ch.toString()
    }

    // ──────────────────────────────────────────────────────────────
    //  Process a keypress on the current word (syllable recomposition)
    // ──────────────────────────────────────────────────────────────

    private fun processWord(word: String, ch: Char): Pair<Int, String> {
        val lowerCh = ch.lowercaseChar()

        if (lowerCh in toneKeys) {
            if (word.isNotEmpty()) {
                val candidate = "${word.last().lowercaseChar()}$lowerCh"
                if (knownOnsets.contains(candidate)) {
                    return word.length to (word + ch)
                }
            }
            val (charsToDelete, tonedWord) = handleTone(word, ch)
            if (charsToDelete == word.length && tonedWord != word + ch) {
                if (!isValidVietnameseWord(tonedWord)) {
                    return word.length to (word + ch)
                }
                return word.length to tonedWord
            }
            return word.length to (word + ch)
        }

        if (lowerCh == 'w' && word.all { it.lowercaseChar() == 'w' }) {
            return word.length to (word + ch)
        }

        val modified = applyDistantModifier(word, ch)
        if (modified != null) {
            if (!isValidVietnameseWord(modified)) {
                return word.length to (word + ch)
            }
            return word.length to modified
        }

        return word.length to (word + ch)
    }

    // ──────────────────────────────────────────────────────────────
    //  Tone handling
    // ──────────────────────────────────────────────────────────────

    private fun handleTone(word: String, ch: Char): Pair<Int, String> {
        val toneKey = ch.lowercaseChar()
        val clean = stripTones(word)

        val syllable = parseSyllable(clean.lowercase())
        if (syllable == null || syllable.nucleus.isEmpty()) {
            return word.length to (word + ch)
        }

        val tonePos = resolveTonePosition(clean, syllable)
        if (tonePos < 0) {
            return word.length to (word + ch)
        }

        val current = word[tonePos]
        val base = toBaseForm(current)
        val toned = toneMaps[toneKey]?.get(base) ?: current

        if (current.lowercaseChar() == toned) {
            val before = word.substring(0, tonePos)
            val after = word.substring(tonePos + 1)
            val casedBase = if (current.isUpperCase()) base.uppercaseChar() else base
            return word.length to (before + casedBase + after + ch)
        }

        val chars = word.toCharArray()
        chars[tonePos] = if (current.isUpperCase()) toned.uppercaseChar() else toned
        return word.length to String(chars)
    }

    private fun handleCancel(precedingText: String): TelexResult? {
        val word = lastWord(precedingText)
        if (word.isEmpty()) return null

        val clean = stripTones(word)
        if (clean == word) {
            // Nothing to cancel, act as normal append
            return null
        }
        return TelexResult(clean, word.length)
    }

    // ──────────────────────────────────────────────────────────────
    //  Distant Modifiers (a, e, o, w, d)
    // ──────────────────────────────────────────────────────────────

    private fun applyDistantModifier(word: String, mod: Char): String? {
        val lowerMod = mod.lowercaseChar()
        if (lowerMod !in listOf('a', 'e', 'o', 'w', 'd')) return null

        val chars = word.toCharArray()

        // 1. UNDO logic
        when (lowerMod) {
            'a' -> if (undoModifier(chars, 'â', 'a')) return String(chars) + mod
            'e' -> if (undoModifier(chars, 'ê', 'e')) return String(chars) + mod
            'o' -> if (undoModifier(chars, 'ô', 'o')) return String(chars) + mod
            'd' -> if (undoModifier(chars, 'đ', 'd')) return String(chars) + mod
            'w' -> {
                val u1 = undoModifier(chars, 'ư', 'u')
                val u2 = undoModifier(chars, 'ơ', 'o')
                val u3 = undoModifier(chars, 'ă', 'a')
                if (u1 || u2 || u3) return String(chars) + mod
            }
        }

        // 2. APPLY logic
        var applied = false
        when (lowerMod) {
            'a' -> applied = applyCircumflex(chars, 'a', 'â')
            'e' -> applied = applyCircumflex(chars, 'e', 'ê')
            'o' -> applied = applyCircumflex(chars, 'o', 'ô')
            'd' -> applied = applyToFirst(chars, 'd', 'đ')
            'w' -> {
                var hasU = false
                var hasO = false
                for (i in chars.indices) {
                    val base = toBaseForm(chars[i])
                    if (base == 'u' && !(i > 0 && chars[i-1].lowercaseChar() == 'q')) hasU = true
                    if (base == 'o') hasO = true
                }

                val clean = stripTones(String(chars))
                if (hasU || hasO) {
                    if (clean.contains("oa") || clean.contains("oe")) {
                        // Apply to 'a' or 'e' if valid
                        for (i in chars.indices.reversed()) {
                            val base = toBaseForm(chars[i])
                            if (base == 'a') {
                                val isLast = (i == chars.size - 1)
                                val lastChar = chars.last().lowercaseChar()
                                val hasConsonantCoda = lastChar in listOf('c', 'm', 'n', 'p', 't', 'g', 'h')
                                if (isLast || hasConsonantCoda) {
                                    chars[i] = changeBaseChar(chars[i], 'ă')
                                    applied = true
                                    break
                                }
                            }
                        }
                    } else {
                        // Apply to 'u' and 'o'
                        for (i in chars.indices) {
                            val base = toBaseForm(chars[i])
                            if (base == 'u') {
                                val isQu = (i > 0 && chars[i-1].lowercaseChar() == 'q')
                                var isCoda = false
                                if (i > 0) {
                                    val prevBase = toBaseForm(chars[i-1])
                                    if (prevBase in listOf('a', 'â', 'ă', 'e', 'ê', 'i', 'y', 'o', 'ô', 'ơ')) {
                                        isCoda = true
                                        if (prevBase == 'i') {
                                            val beforeI = if (i > 1) chars[i-2].lowercaseChar() else ' '
                                            if (beforeI == 'g') {
                                                isCoda = false
                                            }
                                        }
                                    }
                                }
                                if (!isQu && !isCoda) {
                                    chars[i] = changeBaseChar(chars[i], 'ư')
                                    applied = true
                                }
                            }
                            if (base == 'o') {
                                var isCoda = false
                                if (i > 0) {
                                    val prevBase = toBaseForm(chars[i-1])
                                    if (prevBase in listOf('a', 'â', 'ă', 'e', 'ê', 'i', 'y')) {
                                        isCoda = true
                                        if (prevBase == 'i') {
                                            val beforeI = if (i > 1) chars[i-2].lowercaseChar() else ' '
                                            if (beforeI == 'g') {
                                                isCoda = false
                                            }
                                        }
                                    }
                                }
                                if (!isCoda) {
                                    chars[i] = changeBaseChar(chars[i], 'ơ')
                                    applied = true
                                }
                            }
                        }
                    }
                } else {
                    // Apply to 'a'
                    for (i in chars.indices.reversed()) {
                        val base = toBaseForm(chars[i])
                        if (base == 'a') {
                            val isLast = (i == chars.size - 1)
                            val lastChar = chars.last().lowercaseChar()
                            val hasConsonantCoda = lastChar in listOf('c', 'm', 'n', 'p', 't', 'g', 'h')
                            if (isLast || hasConsonantCoda) {
                                chars[i] = changeBaseChar(chars[i], 'ă')
                                applied = true
                                break
                            }
                        }
                    }
                }
            }
        }

        return if (applied) String(chars) else null
    }

    private fun undoModifier(chars: CharArray, targetBase: Char, revertBase: Char): Boolean {
        var modified = false
        for (i in chars.indices) {
            if (toBaseForm(chars[i]) == targetBase) {
                chars[i] = changeBaseChar(chars[i], revertBase)
                modified = true
            }
        }
        return modified
    }

    private fun applyCircumflex(chars: CharArray, targetBase: Char, newBase: Char): Boolean {
        for (i in chars.indices.reversed()) {
            if (toBaseForm(chars[i]) == targetBase) {
                if (i + 1 < chars.size) {
                    val nextChar = chars[i+1].lowercaseChar()
                    if (targetBase == 'a' && (nextChar == 'i' || nextChar == 'o')) continue
                    if (targetBase == 'e' && nextChar == 'o') continue
                    if (targetBase == 'o' && nextChar == 'a') continue
                }
                chars[i] = changeBaseChar(chars[i], newBase)
                return true
            }
        }
        return false
    }

    private fun applyToFirst(chars: CharArray, targetBase: Char, newBase: Char): Boolean {
        for (i in chars.indices) {
            if (toBaseForm(chars[i]) == targetBase) {
                chars[i] = changeBaseChar(chars[i], newBase)
                return true
            }
        }
        return false
    }

    private fun getTone(c: Char): Char? {
        val lower = c.lowercaseChar()
        for ((tone, map) in toneMaps) {
            if (map.values.contains(lower)) return tone
        }
        return null
    }

    private fun changeBaseChar(c: Char, newBase: Char): Char {
        val tone = getTone(c)
        val isUpper = c.isUpperCase()
        val casedNewBase = if (isUpper) newBase.uppercaseChar() else newBase
        if (tone == null) return casedNewBase
        
        val toned = toneMaps[tone]?.get(newBase.lowercaseChar()) ?: newBase.lowercaseChar()
        return if (isUpper) toned.uppercaseChar() else toned
    }

    // ──────────────────────────────────────────────────────────────
    //  Syllable parser
    // ──────────────────────────────────────────────────────────────

    private fun parseSyllable(clean: String): Syllable? {
        if (clean.isEmpty()) return null

        var remaining = clean
        var onset = ""

        for (o in knownOnsets) {
            if (remaining.startsWith(o)) {
                val candidate = remaining.removePrefix(o)
                val hasVowel = candidate.any { toBaseForm(it) in baseVowels }
                val multiEndsInVowel = o.length > 1 && toBaseForm(o.last()) in baseVowels
                if (hasVowel || o.length == 1 || !multiEndsInVowel) {
                    onset = o
                    remaining = candidate
                    break
                }
            }
        }

        if (remaining.isEmpty()) return Syllable(onset = onset)

        var coda = ""

        for (c in knownCodas) {
            if (remaining.endsWith(c)) {
                coda = c
                remaining = remaining.removeSuffix(c)
                break
            }
        }

        if (coda.isNotEmpty() && remaining.isEmpty()) {
            return Syllable(onset = onset, nucleus = "", coda = coda)
        }

        if (remaining.endsWith('u') || remaining.endsWith('i') ||
            remaining.endsWith('y') || remaining.endsWith('o')
        ) {
            val last = remaining.last()
            if (remaining.length > 1 && last in semivowelCodas) {
                val before = remaining.dropLast(1)
                if (before.any { toBaseForm(it) in baseVowels }) {
                    coda = last.toString()
                    remaining = before
                }
            }
        }

        if (remaining.isEmpty()) return Syllable(onset = onset, nucleus = "", coda = coda)

        val nucleus = remaining
        return Syllable(onset = onset, nucleus = nucleus, coda = coda)
    }

    // ──────────────────────────────────────────────────────────────
    //  Tone position resolver (Vietnamese orthographic rules)
    // ──────────────────────────────────────────────────────────────

    private fun resolveTonePosition(word: String, syllable: Syllable): Int {
        val vowelPositions = findVowelPositions(word)
        if (vowelPositions.isEmpty()) return -1
        if (vowelPositions.size == 1) return vowelPositions[0]

        val vowelCluster = buildString {
            for (pos in vowelPositions) {
                append(toBaseForm(word[pos].lowercaseChar()))
            }
        }

        val rule = toneRules[vowelCluster]
        if (rule != null) {
            for (pos in vowelPositions) {
                if (toBaseForm(word[pos].lowercaseChar()) == rule) {
                    return pos
                }
            }
        }

        for (pos in vowelPositions) {
            val b = toBaseForm(word[pos].lowercaseChar())
            if (b == 'ê' || b == 'ơ') return pos
        }

        for (pos in vowelPositions) {
            val b = toBaseForm(word[pos].lowercaseChar())
            if (b == 'â' || b == 'ă' || b == 'ô') return pos
        }

        return vowelPositions.last()
    }

    // ──────────────────────────────────────────────────────────────
    //  Vowel position finder (handles gi/qu exceptions)
    // ──────────────────────────────────────────────────────────────

    private fun findVowelPositions(word: String): List<Int> {
        val lower = word.lowercase()
        val result = mutableListOf<Int>()

        for (i in lower.indices) {
            val c = lower[i]

            if (toBaseForm(c) !in baseVowels) continue

            if (c == 'i' && i == 1 && lower.startsWith("gi") && lower.length > 2) continue

            if (c == 'u' && i == 1 && lower.startsWith("qu") && lower.length > 2) continue

            result.add(i)
        }

        return result
    }

    // ── Phonetic validation (Ma trận âm ngữ) ──────────────────────
    // ──────────────────────────────────────────────────────────────

    private fun lookupSeq(seqs: List<String>, input: String, inputIsFull: Boolean): List<Int>? {
        if (input.isEmpty()) return null
        val ret = mutableListOf<Int>()
        for ((index, row) in seqs.withIndex()) {
            val parts = row.split(" ")
            for (part in parts) {
                if (inputIsFull) {
                    if (part == input) {
                        ret.add(index)
                        break
                    }
                } else {
                    if (part.startsWith(input)) {
                        ret.add(index)
                        break
                    }
                }
            }
        }
        return if (ret.isEmpty()) null else ret
    }

    private fun isValidCVC(fc: String, vo: String, lc: String, inputIsFullComplete: Boolean): Boolean {
        var fcIndexes: List<Int>? = null
        var voIndexes: List<Int>? = null
        var lcIndexes: List<Int>? = null

        if (fc.isNotEmpty()) {
            fcIndexes = lookupSeq(firstConsonantSeqs, fc, inputIsFullComplete || vo.isNotEmpty())
            if (fcIndexes == null) return false
        }
        if (vo.isNotEmpty()) {
            voIndexes = lookupSeq(vowelSeqs, vo, inputIsFullComplete || lc.isNotEmpty())
            if (voIndexes == null) return false
        }
        if (lc.isNotEmpty()) {
            lcIndexes = lookupSeq(lastConsonantSeqs, lc, inputIsFullComplete)
            if (lcIndexes == null) return false
        }

        if (voIndexes == null) {
            // first consonant only
            return fcIndexes != null
        }
        if (fcIndexes != null) {
            // first consonant + vowel
            val isValidCV = fcIndexes.any { f -> cvMatrix[f].any { c -> voIndexes.contains(c) } }
            if (!isValidCV || lcIndexes == null) {
                return isValidCV
            }
        }
        if (lcIndexes != null) {
            // vowel + last consonant
            return voIndexes.any { v -> vcMatrix[v].any { c -> lcIndexes.contains(c) } }
        }
        return true
    }

    private fun isValidVietnameseWord(word: String): Boolean {
        val clean = stripTones(word).lowercase()
        val syllable = parseSyllable(clean) ?: return false
        
        if (isValidCVC(syllable.onset, syllable.nucleus, syllable.coda, false)) {
            return true
        }

        // Special fallback for Bamboo matrix quirks:
        // Bamboo treats "giêng" as g + iê + ng. Our parser gives gi + ê + ng.
        if (syllable.onset == "gi" && syllable.nucleus.isNotEmpty()) {
            if (isValidCVC("g", "i" + syllable.nucleus, syllable.coda, false)) return true
        }
        // Bamboo treats "qu" + "a" as qu + a
        if (syllable.onset == "qu" && syllable.nucleus.isNotEmpty()) {
            if (isValidCVC("q", "u" + syllable.nucleus, syllable.coda, false)) return true
        }

        return false
    }

    // ──────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────

    private fun lastWord(text: String): String {
        val t = text.trimEnd()
        val i = t.lastIndexOf(' ')
        val candidate = if (i < 0) t else t.substring(i + 1)
        return candidate.takeLastWhile { it.isLetter() }
    }

    private fun stripTones(text: String): String {
        return buildString {
            for (c in text) {
                append(toBaseForm(c))
            }
        }
    }

    private fun toBaseForm(c: Char): Char {
        return when (val lower = c.lowercaseChar()) {
            'a', 'á', 'à', 'ả', 'ã', 'ạ' -> 'a'
            'ă', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ' -> 'ă'
            'â', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ' -> 'â'
            'e', 'é', 'è', 'ẻ', 'ẽ', 'ẹ' -> 'e'
            'ê', 'ế', 'ề', 'ể', 'ễ', 'ệ' -> 'ê'
            'i', 'í', 'ì', 'ỉ', 'ĩ', 'ị' -> 'i'
            'o', 'ó', 'ò', 'ỏ', 'õ', 'ọ' -> 'o'
            'ô', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ' -> 'ô'
            'ơ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ' -> 'ơ'
            'u', 'ú', 'ù', 'ủ', 'ũ', 'ụ' -> 'u'
            'ư', 'ứ', 'ừ', 'ử', 'ữ', 'ự' -> 'ư'
            'y', 'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ' -> 'y'
            else -> lower
        }
    }

}
