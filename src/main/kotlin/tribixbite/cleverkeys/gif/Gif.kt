package tribixbite.cleverkeys.gif

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Represents a single GIF entry from an online provider (Tenor, Giphy).
 */
data class Gif(
    val id: String,
    val thumbnailUrl: String,
    val fullUrl: String,
    val width: Int,
    val height: Int,
    val title: String,
    val source: String // "tenor" or "giphy"
) {
    /**
     * Get the aspect ratio for layout calculations.
     */
    fun getAspectRatio(): Float = if (height > 0) width.toFloat() / height else 1f

    companion object {
        /**
         * Get the file for a GIF in the cache directory.
         * Used when GIFs need to be shared to other apps via FileProvider.
         */
        fun getCacheFile(context: Context, gif: Gif): File {
            val cacheDir = File(context.cacheDir, "gifs")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val extension = if (gif.fullUrl.endsWith(".webp", ignoreCase = true)) "webp" else "gif"
            return File(cacheDir, "${gif.id}.$extension")
        }
    }
}
