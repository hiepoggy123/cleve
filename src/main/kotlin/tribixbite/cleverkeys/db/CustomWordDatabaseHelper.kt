package tribixbite.cleverkeys.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class CustomWordDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    data class CustomWordData(val word: String, val frequency: Int, val shortcut: String?, val language: String)

    companion object {
        private const val TAG = "CustomWordDB"
        private const val DATABASE_NAME = "custom_words.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_NAME = "custom_words"
        const val COL_ID = "_id"
        const val COL_WORD = "word"
        const val COL_FREQUENCY = "frequency"
        const val COL_SHORTCUT = "shortcut"
        const val COL_LANGUAGE = "language"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_WORD TEXT NOT NULL,
                $COL_FREQUENCY INTEGER NOT NULL DEFAULT 1000,
                $COL_SHORTCUT TEXT,
                $COL_LANGUAGE TEXT NOT NULL,
                UNIQUE($COL_WORD, $COL_LANGUAGE) ON CONFLICT REPLACE
            )
        """.trimIndent()
        db.execSQL(createTable)

        db.execSQL("CREATE INDEX idx_language ON $TABLE_NAME($COL_LANGUAGE)")
        db.execSQL("CREATE INDEX idx_word ON $TABLE_NAME($COL_WORD)")
        Log.d(TAG, "Database created with table $TABLE_NAME")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Not needed for version 1
    }

    fun insertOrUpdateWord(word: String, frequency: Int, shortcut: String?, language: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_WORD, word)
            put(COL_FREQUENCY, frequency)
            put(COL_SHORTCUT, shortcut)
            put(COL_LANGUAGE, language)
        }
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteWord(word: String, language: String) {
        val db = writableDatabase
        db.delete(TABLE_NAME, "$COL_WORD = ? AND $COL_LANGUAGE = ?", arrayOf(word, language))
    }

    fun clearAllWords(language: String) {
        val db = writableDatabase
        db.delete(TABLE_NAME, "$COL_LANGUAGE = ?", arrayOf(language))
    }

    fun getAllWords(language: String): List<CustomWordData> {
        val words = mutableListOf<CustomWordData>()
        val db = readableDatabase
        db.query(
            TABLE_NAME,
            null,
            "$COL_LANGUAGE = ?",
            arrayOf(language),
            null,
            null,
            "$COL_WORD ASC"
        ).use { cursor ->
            val idxWord = cursor.getColumnIndexOrThrow(COL_WORD)
            val idxFreq = cursor.getColumnIndexOrThrow(COL_FREQUENCY)
            val idxShortcut = cursor.getColumnIndexOrThrow(COL_SHORTCUT)
            
            while (cursor.moveToNext()) {
                val word = cursor.getString(idxWord)
                val freq = cursor.getInt(idxFreq)
                val shortcut = if (cursor.isNull(idxShortcut)) null else cursor.getString(idxShortcut)
                words.add(CustomWordData(word, freq, shortcut, language))
            }
        }
        return words
    }
    
    fun searchWords(query: String, language: String): List<CustomWordData> {
        val words = mutableListOf<CustomWordData>()
        val db = readableDatabase
        db.query(
            TABLE_NAME,
            null,
            "$COL_LANGUAGE = ? AND $COL_WORD LIKE ?",
            arrayOf(language, "%${query}%"),
            null,
            null,
            "$COL_WORD ASC"
        ).use { cursor ->
            val idxWord = cursor.getColumnIndexOrThrow(COL_WORD)
            val idxFreq = cursor.getColumnIndexOrThrow(COL_FREQUENCY)
            val idxShortcut = cursor.getColumnIndexOrThrow(COL_SHORTCUT)
            
            while (cursor.moveToNext()) {
                val word = cursor.getString(idxWord)
                val freq = cursor.getInt(idxFreq)
                val shortcut = if (cursor.isNull(idxShortcut)) null else cursor.getString(idxShortcut)
                words.add(CustomWordData(word, freq, shortcut, language))
            }
        }
        return words
    }
}
