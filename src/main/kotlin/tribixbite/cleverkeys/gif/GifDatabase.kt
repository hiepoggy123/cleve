package tribixbite.cleverkeys.gif

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SQLite database handler for tracking recently used GIFs from online sources.
 * The legacy offline FTS database structure has been replaced by this minimal version.
 */
class GifDatabase private constructor(private val appContext: Context) {

    private val dbHelper: GifDatabaseHelper = GifDatabaseHelper(appContext)

    /**
     * Record a GIF as being used (updates timestamp or inserts new).
     */
    suspend fun recordGifUsage(gif: Gif) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("id", gif.id)
            put("thumbnail_url", gif.thumbnailUrl)
            put("full_url", gif.fullUrl)
            put("width", gif.width)
            put("height", gif.height)
            put("title", gif.title)
            put("source", gif.source)
            put("last_used", System.currentTimeMillis())
        }
        db.insertWithOnConflict("recently_used", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Get the most recently used GIFs.
     */
    suspend fun getRecentlyUsedGifs(limit: Int = 50): List<Gif> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val results = mutableListOf<Gif>()
        
        db.rawQuery(
            "SELECT id, thumbnail_url, full_url, width, height, title, source FROM recently_used ORDER BY last_used DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    Gif(
                        id = cursor.getString(0),
                        thumbnailUrl = cursor.getString(1),
                        fullUrl = cursor.getString(2),
                        width = cursor.getInt(3),
                        height = cursor.getInt(4),
                        title = cursor.getString(5),
                        source = cursor.getString(6)
                    )
                )
            }
        }
        return@withContext results
    }

    /**
     * Clear all recently used GIFs.
     */
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.execSQL("DELETE FROM recently_used")
    }

    companion object {
        const val DATABASE_NAME = "gif_recent.db"
        const val DATABASE_VERSION = 1

        @Volatile
        private var instance: GifDatabase? = null

        fun getInstance(context: Context): GifDatabase {
            return instance ?: synchronized(this) {
                instance ?: GifDatabase(context.applicationContext).also { instance = it }
            }
        }
    }
}

class GifDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    GifDatabase.DATABASE_NAME,
    null,
    GifDatabase.DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE recently_used (
                id TEXT PRIMARY KEY,
                thumbnail_url TEXT NOT NULL,
                full_url TEXT NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                title TEXT NOT NULL,
                source TEXT NOT NULL,
                last_used INTEGER NOT NULL
            )
        """.trimIndent())
        
        db.execSQL("CREATE INDEX idx_last_used ON recently_used(last_used DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS recently_used")
        onCreate(db)
    }
}
