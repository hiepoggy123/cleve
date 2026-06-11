package tribixbite.cleverkeys.gif

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Provides GIFs from the Tenor API (v2).
 */
class TenorGifProvider(private val apiKey: String) : OnlineGifProvider {
    
    private val baseUrl = "https://tenor.googleapis.com/v2"

    override suspend fun search(query: String, limit: Int, offset: Int): List<Gif> {
        if (apiKey.isBlank()) return emptyList()
        val posParam = if (offset > 0) "&pos=$offset" else ""
        val url = "$baseUrl/search?q=${URLEncoder.encode(query, "UTF-8")}&key=$apiKey&limit=$limit$posParam"
        return fetchGifs(url)
    }

    override suspend fun getTrending(limit: Int, offset: Int): List<Gif> {
        if (apiKey.isBlank()) return emptyList()
        val posParam = if (offset > 0) "&pos=$offset" else ""
        val url = "$baseUrl/featured?key=$apiKey&limit=$limit$posParam"
        return fetchGifs(url)
    }

    private suspend fun fetchGifs(urlString: String): List<Gif> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Gif>()
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val jsonObject = JSONObject(response)
                if (jsonObject.has("results")) {
                    val results = jsonObject.getJSONArray("results")
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val id = item.getString("id")
                        val title = if (item.has("title")) item.getString("title") else ""
                        
                        val mediaFormats = item.getJSONObject("media_formats")
                        
                        // Fallbacks for thumbnails
                        val thumbFormat = when {
                            mediaFormats.has("tinygif") -> mediaFormats.getJSONObject("tinygif")
                            mediaFormats.has("nanogif") -> mediaFormats.getJSONObject("nanogif")
                            mediaFormats.has("gif") -> mediaFormats.getJSONObject("gif")
                            else -> null
                        }
                        
                        val fullFormat = when {
                            mediaFormats.has("gif") -> mediaFormats.getJSONObject("gif")
                            else -> null
                        }

                        if (thumbFormat != null && fullFormat != null) {
                            val thumbnailUrl = thumbFormat.getString("url")
                            val fullUrl = fullFormat.getString("url")
                            val dims = fullFormat.getJSONArray("dims")
                            val width = dims.getInt(0)
                            val height = dims.getInt(1)

                            result.add(
                                Gif(
                                    id = id,
                                    thumbnailUrl = thumbnailUrl,
                                    fullUrl = fullUrl,
                                    width = width,
                                    height = height,
                                    title = title,
                                    source = "tenor"
                                )
                            )
                        }
                    }
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext result
    }
}
