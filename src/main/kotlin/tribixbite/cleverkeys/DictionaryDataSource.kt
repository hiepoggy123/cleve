package tribixbite.cleverkeys

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.provider.UserDictionary
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Interface for dictionary data sources
 */
interface DictionaryDataSource {
    suspend fun getAllWords(): List<DictionaryWord>
    suspend fun searchWords(query: String): List<DictionaryWord>
    suspend fun toggleWord(word: String, enabled: Boolean)
    suspend fun addWord(word: String, frequency: Int = 100, shortcut: String? = null)
    suspend fun deleteWord(word: String)
    suspend fun updateWord(oldWord: String, newWord: String, frequency: Int, shortcut: String? = null)

    /**
     * Re-sync cached state from persistent storage.
     * Called by [WordListFragment.refresh] before re-filtering, so that changes made
     * by a different data source (e.g. DisabledDictionarySource toggling a word that
     * MainDictionarySource has cached) are reflected.
     * Default no-op — only sources with in-memory caches need to override.
     */
    fun onRefresh() {}
}

/**
 * Main dictionary source - loads from assets dictionary file
 * Uses prefix indexing for fast search with 50k vocabulary
 *
 * v1.1.89: Added language support - loads language-specific dictionary when available
 *
 * @param languageCode ISO 639-1 language code (e.g., "en", "fr", "es"). Defaults to "en".
 */
class MainDictionarySource(
    private val context: Context,
    private val disabledSource: DisabledDictionarySource,
    private val languageCode: String = "en"
) : DictionaryDataSource {

    // Instance-level cache references (point to shared static cache when language matches)
    private var cachedWords: List<DictionaryWord>? = null
    // Prefix index for fast search: prefix -> list of matching words
    private var prefixIndex: Map<String, List<DictionaryWord>>? = null

    init {
        // Reuse shared cache if it was built for this language.
        // This avoids re-parsing the 50k binary dictionary every time
        // the user opens Dictionary Manager or switches tabs.
        synchronized(Companion) {
            sharedCache[languageCode]?.let { cached ->
                cachedWords = cached.words
                prefixIndex = cached.prefixIndex
            }
        }
    }

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        Log.d(TAG, "getAllWords() called for language: $languageCode")

        // Return cached if available (instance-level fast path)
        if (cachedWords != null) {
            Log.d(TAG, "Returning ${cachedWords!!.size} cached words for $languageCode")
            return@withContext cachedWords!!
        }

        // Double-check shared cache: another fragment for this language may have
        // finished loading while we were waiting for the IO dispatcher
        synchronized(Companion) {
            sharedCache[languageCode]?.let { cached ->
                cachedWords = cached.words
                prefixIndex = cached.prefixIndex
                Log.d(TAG, "Returning ${cached.words.size} words from shared cache for $languageCode")
                return@withContext cached.words
            }
        }

        try {
            // USER REQUEST: "Bạn chưa tắt hoặc xoá từ điển mặc định kìa"
            // Bypass loading the default dictionaries completely so that ONLY custom words and shortcuts are used.
            val emptyWords = emptyList<DictionaryWord>()
            cachedWords = emptyWords
            prefixIndex = emptyMap()
            synchronized(Companion) {
                sharedCache[languageCode] = LanguageCache(emptyWords, emptyMap())
            }
            return@withContext emptyWords
        } catch (e: Exception) {
            Log.e(TAG, "Error loading main dictionary", e)
            emptyList()
        }
    }

    /**
     * Build prefix index for fast word search.
     * Creates mapping from prefixes (1-3 chars) to lists of matching words.
     * Performance: Reduces 50k linear search to ~100-500 comparisons.
     * Also updates shared static cache so subsequent MainDictionarySource
     * instances for the same language skip the full load.
     */
    private fun buildPrefixIndex(words: List<DictionaryWord>) {
        val index = mutableMapOf<String, MutableList<DictionaryWord>>()

        for (word in words) {
            val maxLen = minOf(PREFIX_INDEX_MAX_LENGTH, word.word.length)
            for (len in 1..maxLen) {
                val prefix = word.word.substring(0, len).lowercase()
                index.getOrPut(prefix) { mutableListOf() }.add(word)
            }
        }

        prefixIndex = index
        Log.d(TAG, "Built prefix index: ${index.size} prefixes for ${words.size} words")

        // Persist to shared static cache for cross-instance reuse
        synchronized(Companion) {
            sharedCache[languageCode] = LanguageCache(words, index)
        }
    }

    override suspend fun searchWords(query: String): List<DictionaryWord> {
        if (query.isBlank()) return getAllWords()

        val lowerQuery = query.lowercase()

        // Use prefix index if query starts at beginning of word (most common case)
        if (lowerQuery.length <= PREFIX_INDEX_MAX_LENGTH) {
            // Exact prefix match - use index
            val candidates = prefixIndex?.get(lowerQuery) ?: emptyList()
            // Filter for substring match (in case user typed middle of word)
            return candidates.filter { it.word.contains(lowerQuery, ignoreCase = true) }
        } else if (lowerQuery.length > PREFIX_INDEX_MAX_LENGTH) {
            // Use first 3 chars from index, then filter
            val prefix = lowerQuery.substring(0, PREFIX_INDEX_MAX_LENGTH)
            val candidates = prefixIndex?.get(prefix) ?: emptyList()
            return candidates.filter { it.word.contains(lowerQuery, ignoreCase = true) }
        }

        // Fallback to full search (should rarely happen)
        return getAllWords().filter { it.word.contains(query, ignoreCase = true) }
    }

    /**
     * Re-sync all cached DictionaryWord.enabled flags from the current disabled set.
     * Handles cross-source coherence: when DisabledDictionarySource toggles a word,
     * this source's cache becomes stale. Called by WordListFragment.refresh() before
     * re-filtering. O(n) scan of ~50k words against a HashSet — <5ms.
     *
     * The prefix index stores the SAME DictionaryWord object references as cachedWords,
     * so mutating .enabled here also fixes prefix index search results.
     */
    override fun onRefresh() {
        cachedWords?.let { words ->
            val disabled = disabledSource.getDisabledWords()
            for (dw in words) {
                dw.enabled = !disabled.contains(dw.word)
            }
        }
    }

    override suspend fun toggleWord(word: String, enabled: Boolean) {
        disabledSource.setWordEnabled(word, enabled)
        // Update cached entries in-place so subsequent getAllWords()/searchWords()
        // reflect the new enabled state without a full 50k-word reload
        cachedWords?.forEach { dw ->
            if (dw.word.equals(word, ignoreCase = true)) {
                dw.enabled = enabled
            }
        }
    }

    override suspend fun addWord(word: String, frequency: Int, shortcut: String?) {
        // Main dictionary is read-only
        throw UnsupportedOperationException("Cannot add words to main dictionary")
    }

    override suspend fun deleteWord(word: String) {
        // Main dictionary is read-only
        throw UnsupportedOperationException("Cannot delete words from main dictionary")
    }

    override suspend fun updateWord(oldWord: String, newWord: String, frequency: Int, shortcut: String?) {
        // Main dictionary is read-only
        throw UnsupportedOperationException("Cannot update words in main dictionary")
    }

    /**
     * Load dictionary from binary file (File object, for language packs).
     * v1.1.96: Added for language pack support.
     */
    private fun loadBinaryDictionaryFromFile(
        file: java.io.File,
        words: MutableList<DictionaryWord>,
        disabled: Set<String>
    ): Boolean {
        return try {
            val index = NormalizedPrefixIndex()
            val loaded = BinaryDictionaryLoader.loadIntoNormalizedIndexFromFile(file, index)
            if (loaded) {
                extractWordsFromIndex(index, words, disabled)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading binary dictionary from file: ${file.absolutePath}", e)
            false
        }
    }

    /**
     * Load dictionary from binary format (.bin files for non-English languages).
     * Uses NormalizedPrefixIndex to read the binary format.
     */
    private fun loadBinaryDictionary(
        filename: String,
        words: MutableList<DictionaryWord>,
        disabled: Set<String>
    ): Boolean {
        return try {
            val index = NormalizedPrefixIndex()
            val loaded = BinaryDictionaryLoader.loadIntoNormalizedIndex(context, filename, index)
            if (loaded) {
                extractWordsFromIndex(index, words, disabled)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading binary dictionary: $filename", e)
            false
        }
    }

    /**
     * Extract words from NormalizedPrefixIndex into word list.
     * Shared by both loadBinaryDictionary() and loadBinaryDictionaryFromFile().
     */
    private fun extractWordsFromIndex(
        index: NormalizedPrefixIndex,
        words: MutableList<DictionaryWord>,
        disabled: Set<String>
    ) {
        val normalizedWords = index.getAllNormalizedWords()
        for (word in normalizedWords) {
            // Get canonical form (with accents) and frequency rank
            val results = index.getWordsWithPrefix(word)
            val match = results.find { it.normalized == word }
            val canonical = match?.bestCanonical ?: word
            // Convert rank (0-255, 0=most common) to display frequency (1-10000)
            // rank 0 → 10000, rank 255 → 1
            val rank = match?.bestFrequencyRank ?: 255
            val frequency = 10000 - (rank * 39)  // ~10000 to ~50
            words.add(
                DictionaryWord(
                    word = canonical,  // Show accented form
                    frequency = frequency.coerceIn(1, 10000),
                    source = WordSource.MAIN,
                    enabled = !disabled.contains(word) && !disabled.contains(canonical)
                )
            )
        }
    }

    companion object {
        private const val TAG = "MainDictionarySource"
        private const val PREFIX_INDEX_MAX_LENGTH = 3

        // Per-language shared cache across MainDictionarySource instances.
        // Eliminates redundant 50k-word binary dict parsing when DictionaryManager
        // is reopened or tabs are switched (each fragment creates a new instance).
        // Keyed by language code so multilang tabs don't evict each other.
        // Thread-safe: synchronized on Companion in init{} and buildPrefixIndex().
        private data class LanguageCache(
            val words: List<DictionaryWord>,
            val prefixIndex: Map<String, List<DictionaryWord>>
        )
        private val sharedCache = mutableMapOf<String, LanguageCache>()

        /** Invalidate shared cache for all languages. */
        fun invalidateCache() {
            synchronized(this) {
                sharedCache.clear()
            }
        }

        /** Invalidate shared cache for a specific language. */
        fun invalidateCache(languageCode: String) {
            synchronized(this) {
                sharedCache.remove(languageCode)
            }
        }
    }
}

/**
 * Disabled words source - manages disabled word list.
 *
 * @param prefs SharedPreferences to use
 * @param languageCode ISO 639-1 language code (e.g., "en", "es") for language-specific storage.
 *                     If null, uses global key (legacy behavior).
 * @since v1.1.86 - Added language-specific storage support
 */
class DisabledDictionarySource(
    private val prefs: SharedPreferences,
    private val languageCode: String? = null
) : DictionaryDataSource {

    /**
     * Get the preference key for disabled words.
     * Uses language-specific key if languageCode is provided, otherwise legacy global key.
     */
    private val disabledWordsKey: String
        get() = if (languageCode != null) {
            LanguagePreferenceKeys.disabledWordsKey(languageCode)
        } else {
            PREF_DISABLED_WORDS_LEGACY
        }

    fun getDisabledWords(): Set<String> {
        return prefs.getStringSet(disabledWordsKey, emptySet()) ?: emptySet()
    }

    fun setWordEnabled(word: String, enabled: Boolean) {
        val disabled = getDisabledWords().toMutableSet()
        if (enabled) {
            disabled.remove(word)
        } else {
            disabled.add(word)
        }
        prefs.edit().putStringSet(disabledWordsKey, disabled).apply()
    }

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        getDisabledWords()
            .map { DictionaryWord(it, 0, WordSource.MAIN, false) }
            .sorted()
    }

    override suspend fun searchWords(query: String): List<DictionaryWord> {
        if (query.isBlank()) return getAllWords()
        return getAllWords().filter { it.word.contains(query, ignoreCase = true) }
    }

    override suspend fun toggleWord(word: String, enabled: Boolean) {
        setWordEnabled(word, enabled)
    }

    override suspend fun addWord(word: String, frequency: Int, shortcut: String?) {
        // Disabled list doesn't support adding
        throw UnsupportedOperationException("Use toggleWord instead")
    }

    override suspend fun deleteWord(word: String) {
        setWordEnabled(word, true) // Re-enable word
    }

    override suspend fun updateWord(oldWord: String, newWord: String, frequency: Int, shortcut: String?) {
        // Disabled list doesn't support updating
        throw UnsupportedOperationException("Use toggleWord instead")
    }

    companion object {
        // Legacy global key (pre-v1.1.86)
        private const val PREF_DISABLED_WORDS_LEGACY = "disabled_words"
    }
}

/**
 * User dictionary source - reads from Android's UserDictionary
 */
class UserDictionarySource(
    private val context: Context,
    private val contentResolver: ContentResolver
) : DictionaryDataSource {

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        try {
            val words = mutableListOf<DictionaryWord>()
            val cursor = contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(
                    UserDictionary.Words.WORD,
                    UserDictionary.Words.FREQUENCY,
                    UserDictionary.Words.SHORTCUT
                ),
                null,
                null,
                "${UserDictionary.Words.WORD} ASC"
            )

            cursor?.use {
                val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                val freqIndex = it.getColumnIndex(UserDictionary.Words.FREQUENCY)
                val shortcutIndex = it.getColumnIndex(UserDictionary.Words.SHORTCUT)

                while (it.moveToNext()) {
                    val word = it.getString(wordIndex)
                    val freq = if (freqIndex >= 0) it.getInt(freqIndex) else 100
                    val shortcut = if (shortcutIndex >= 0) it.getString(shortcutIndex) else null
                    words.add(DictionaryWord(word, freq, WordSource.USER, true, shortcut))
                }
            }

            words.sorted()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user dictionary", e)
            emptyList()
        }
    }

    override suspend fun searchWords(query: String): List<DictionaryWord> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getAllWords()

        try {
            val words = mutableListOf<DictionaryWord>()
            val selection = "${UserDictionary.Words.WORD} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            val cursor = contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(
                    UserDictionary.Words.WORD,
                    UserDictionary.Words.FREQUENCY,
                    UserDictionary.Words.SHORTCUT
                ),
                selection,
                selectionArgs,
                "${UserDictionary.Words.WORD} ASC"
            )

            cursor?.use {
                val wordIndex = it.getColumnIndex(UserDictionary.Words.WORD)
                val freqIndex = it.getColumnIndex(UserDictionary.Words.FREQUENCY)
                val shortcutIndex = it.getColumnIndex(UserDictionary.Words.SHORTCUT)

                while (it.moveToNext()) {
                    val word = it.getString(wordIndex)
                    val freq = if (freqIndex >= 0) it.getInt(freqIndex) else 100
                    val shortcut = if (shortcutIndex >= 0) it.getString(shortcutIndex) else null
                    words.add(DictionaryWord(word, freq, WordSource.USER, true, shortcut))
                }
            }

            words.sorted()
        } catch (e: Exception) {
            Log.e(TAG, "Error searching user dictionary", e)
            emptyList()
        }
    }

    override suspend fun toggleWord(word: String, enabled: Boolean) {
        // User dictionary doesn't support disabling, only deleting
        if (!enabled) deleteWord(word)
    }

    override suspend fun addWord(word: String, frequency: Int, shortcut: String?) = withContext(Dispatchers.IO) {
        // Use UserDictionary API to add word
        UserDictionary.Words.addWord(
            context,
            word,
            frequency,
            shortcut,
            null
        )
    }

    override suspend fun deleteWord(word: String): Unit = withContext(Dispatchers.IO) {
        contentResolver.delete(
            UserDictionary.Words.CONTENT_URI,
            "${UserDictionary.Words.WORD}=?",
            arrayOf(word)
        )
        Unit
    }

    override suspend fun updateWord(oldWord: String, newWord: String, frequency: Int, shortcut: String?) {
        deleteWord(oldWord)
        addWord(newWord, frequency, shortcut)
    }

    companion object {
        private const val TAG = "UserDictionarySource"
    }
}

/**
 * Custom dictionary source - app-specific custom words (language-aware)
 *
 * v1.1.87: Now uses language-specific storage via LanguagePreferenceKeys.
 * This matches how OptimizedVocabulary stores custom words for swipe prediction.
 *
 * @param prefs SharedPreferences for storage (typically DirectBootAwarePreferences)
 * @param languageCode Language code for language-specific storage (e.g., "en", "fr")
 *                     If null, uses legacy global key "custom_words" for backwards compatibility
 */
class CustomDictionarySource(
    private val prefs: SharedPreferences,
    private val languageCode: String? = null
) : DictionaryDataSource {

    private val gson = Gson()

    // Use language-specific key when languageCode is provided
    private val customWordsKey: String = if (languageCode != null) {
        LanguagePreferenceKeys.customWordsKey(languageCode)
    } else {
        PREF_CUSTOM_WORDS_LEGACY
    }

    data class CustomWordData(val frequency: Int, val shortcut: String?)

    private fun getCustomWords(): MutableMap<String, CustomWordData> {
        val jsonString = prefs.getString(customWordsKey, "{}") ?: "{}"
        val result = mutableMapOf<String, CustomWordData>()
        if (jsonString != "{}") {
            try {
                val jsonObj = org.json.JSONObject(jsonString)
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsonObj.get(key)
                    if (value is Int) {
                        result[key] = CustomWordData(value, null)
                    } else if (value is org.json.JSONObject) {
                        val freq = value.optInt("f", 1000)
                        val shortcut = value.optString("s", null).takeIf { !it.isNullOrEmpty() }
                        result[key] = CustomWordData(freq, shortcut)
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }
        return result
    }

    private fun saveCustomWords(words: Map<String, CustomWordData>) {
        try {
            val jsonObj = org.json.JSONObject()
            for ((key, data) in words) {
                if (data.shortcut == null) {
                    jsonObj.put(key, data.frequency)
                } else {
                    val obj = org.json.JSONObject()
                    obj.put("f", data.frequency)
                    obj.put("s", data.shortcut)
                    jsonObj.put(key, obj)
                }
            }
            prefs.edit().putString(customWordsKey, jsonObj.toString()).apply()
        } catch (e: Exception) {
            // Ignore serialize errors
        }
    }

    override suspend fun getAllWords(): List<DictionaryWord> = withContext(Dispatchers.IO) {
        getCustomWords()
            .map { (word, data) ->
                DictionaryWord(word, data.frequency, WordSource.CUSTOM, true, data.shortcut)
            }
            .sorted()
    }

    override suspend fun searchWords(query: String): List<DictionaryWord> {
        if (query.isBlank()) return getAllWords()
        return getAllWords().filter { it.word.contains(query, ignoreCase = true) }
    }

    override suspend fun toggleWord(word: String, enabled: Boolean) {
        // Custom words are always enabled, use delete to remove
        if (!enabled) deleteWord(word)
    }

    override suspend fun addWord(word: String, frequency: Int, shortcut: String?) {
        val words = getCustomWords()
        words[word] = CustomWordData(frequency, shortcut)
        saveCustomWords(words)
    }

    override suspend fun deleteWord(word: String) {
        val words = getCustomWords()
        words.remove(word)
        saveCustomWords(words)
    }

    override suspend fun updateWord(oldWord: String, newWord: String, frequency: Int, shortcut: String?) {
        val words = getCustomWords()
        words.remove(oldWord)
        words[newWord] = CustomWordData(frequency, shortcut)
        saveCustomWords(words)
    }

    companion object {
        // Legacy key for backwards compatibility (used when languageCode is null)
        private const val PREF_CUSTOM_WORDS_LEGACY = "custom_words"
    }
}