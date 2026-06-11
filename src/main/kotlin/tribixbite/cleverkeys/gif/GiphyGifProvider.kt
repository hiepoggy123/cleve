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
 * Provides GIFs from the Giphy API.
 */
class GiphyGifProvider(private val apiKey: String) : OnlineGifProvider {
    
    private val baseUrl = "https://api.giphy.com/v1/gifs"

    override suspend fun search(query: String, limit: Int, offset: Int): List<Gif> {
        if (apiKey.isBlank()) return emptyList()
        val url = "$baseUrl/search?api_key=$apiKey&q=${URLEncoder.encode(query, "UTF-8")}&limit=$limit&offset=$offset"
        return fetchGifs(url)
    }

    override suspend fun getTrending(limit: Int, offset: Int): List<Gif> {
        if (apiKey.isBlank()) return emptyList()
        val url = "$baseUrl/trending?api_key=$apiKey&limit=$limit&offset=$offset"
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
                if (jsonObject.has("data")) {
                    val data = jsonObject.getJSONArray("data")
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        val id = item.getString("id")
                        val title = if (item.has("title")) item.getString("title") else ""
                        
                        val images = item.getJSONObject("images")
                        
                        val thumbFormat = when {
                            images.has("fixed_height_small") -> images.getJSONObject("fixed_height_small")
                            images.has("fixed_height") -> images.getJSONObject("fixed_height")
                            images.has("original") -> images.getJSONObject("original")
                            else -> null
                        }
                        
                        val fullFormat = when {
                            images.has("original") -> images.getJSONObject("original")
                            else -> null
                        }

                        if (thumbFormat != null && fullFormat != null) {
                            val thumbnailUrl = thumbFormat.getString("url")
                            val fullUrl = fullFormat.getString("url")
                            // Giphy provides width/height as strings
                            val width = fullFormat.getString("width").toIntOrNull() ?: 200
                            val height = fullFormat.getString("height").toIntOrNull() ?: 200

                            result.add(
                                Gif(
                                    id = id,
                                    thumbnailUrl = thumbnailUrl,
                                    fullUrl = fullUrl,
                                    width = width,
                                    height = height,
                                    title = title,
                                    source = "giphy"
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
