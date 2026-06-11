package tribixbite.cleverkeys.gif

/**
 * Interface for online GIF providers (e.g. Tenor, Giphy).
 */
interface OnlineGifProvider {
    /**
     * Search for GIFs based on a query string.
     */
    suspend fun search(query: String, limit: Int = 50, offset: Int = 0): List<Gif>

    /**
     * Get trending or featured GIFs.
     */
    suspend fun getTrending(limit: Int = 50, offset: Int = 0): List<Gif>
}
