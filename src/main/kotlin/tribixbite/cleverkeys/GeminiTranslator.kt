package tribixbite.cleverkeys

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GeminiTranslator {
    private const val TAG = "GeminiTranslator"

    /**
     * Translates the given text using the Gemini API.
     * Automatically translates to English if the text is in Vietnamese,
     * or to Vietnamese if it's in English.
     */
    suspend fun translate(text: String, apiKey: String, model: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) {
            return@withContext null
        }

        val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true

            // Build JSON payload
            val prompt = "Translate the following text to English (if it's in Vietnamese) or to Vietnamese (if it's in English). Only return the translated text without any other explanations or markdown formatting:\n\n$text"
            
            val jsonPayload = JSONObject().apply {
                put("contents", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }

            // Write payload
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonPayload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Read response
                val responseString = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }

                // Parse response
                val jsonResponse = JSONObject(responseString)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        return@withContext parts.getJSONObject(0).optString("text").trim()
                    }
                }
            } else {
                val errorString = BufferedReader(InputStreamReader(connection.errorStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
                Log.e(TAG, "Gemini API error ($responseCode): $errorString")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call Gemini API", e)
        }
        
        return@withContext null
    }
}
