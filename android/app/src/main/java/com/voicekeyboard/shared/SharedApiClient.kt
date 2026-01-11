package com.voicekeyboard.shared

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared API client for transcription requests.
 * Used by both React Native app and IME service.
 * 
 * API Key Authentication:
 * - API key can be included in the URL as query param: http://server:3002?api_key=xxx
 * - The client will extract and forward it with requests
 */
class SharedApiClient(
    // Default URL - will be overwritten by KeyboardStateManager.apiUrl
    private var baseUrl: String = "http://localhost:3002"
) {
    
    companion object {
        private const val TAG = "SharedApiClient"
        private const val CONNECT_TIMEOUT = 10000 // 10 seconds
        private const val READ_TIMEOUT = 30000 // 30 seconds
    }
    
    // API key extracted from URL (if present)
    private var apiKey: String = ""
    
    data class TranscriptionResult(
        val success: Boolean,
        val text: String?,
        val error: String?
    )
    
    /**
     * Set the base URL for the API
     * Supports URL with api_key query param: http://server:3002?api_key=xxx
     */
    fun setBaseUrl(url: String) {
        val trimmedUrl = url.trimEnd('/')
        
        // Extract API key from URL if present
        try {
            val uri = java.net.URI(trimmedUrl)
            val query = uri.query
            if (query != null && query.contains("api_key=")) {
                // Extract API key
                val params = query.split("&")
                for (param in params) {
                    if (param.startsWith("api_key=")) {
                        apiKey = param.substringAfter("api_key=")
                        break
                    }
                }
                // Store base URL without query params
                baseUrl = "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}${uri.path}"
                Log.d(TAG, "API key extracted from URL, base: $baseUrl")
            } else {
                baseUrl = trimmedUrl
                apiKey = ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse URL, using as-is: $trimmedUrl")
            baseUrl = trimmedUrl
            apiKey = ""
        }
    }
    
    /**
     * Get the current base URL (without API key)
     */
    fun getBaseUrl(): String = baseUrl
    
    /**
     * Transcribe audio data (Base64-encoded WAV)
     */
    suspend fun transcribe(base64Audio: String): TranscriptionResult {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/transcribe")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    // Add API key header if configured
                    if (apiKey.isNotEmpty()) {
                        setRequestProperty("X-API-Key", apiKey)
                    }
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                }
                
                // Write request body
                val requestBody = JSONObject().apply {
                    put("audio", base64Audio)
                }
                
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }
                
                // Read response
                val responseCode = connection.responseCode
                Log.d(TAG, "Transcription response code: $responseCode")
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                    val jsonResponse = JSONObject(response)
                    
                    val text = jsonResponse.optString("text", "")
                    Log.d(TAG, "Transcription result: $text")
                    
                    TranscriptionResult(
                        success = true,
                        text = text,
                        error = null
                    )
                } else {
                    val errorResponse = try {
                        connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                    } catch (e: Exception) {
                        null
                    }
                    
                    Log.e(TAG, "Transcription failed: $responseCode - $errorResponse")
                    
                    // Provide user-friendly error messages
                    val errorMessage = when (responseCode) {
                        HttpURLConnection.HTTP_UNAUTHORIZED -> 
                            "Authentication failed. Please check your API key in settings."
                        HttpURLConnection.HTTP_FORBIDDEN -> 
                            "Access denied. Invalid API key."
                        else -> 
                            "HTTP $responseCode: ${errorResponse ?: "Unknown error"}"
                    }
                    
                    TranscriptionResult(
                        success = false,
                        text = null,
                        error = errorMessage
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                TranscriptionResult(
                    success = false,
                    text = null,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }
    
    /**
     * Check if the backend server is reachable
     */
    suspend fun ping(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/health")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                
                val responseCode = connection.responseCode
                connection.disconnect()
                
                responseCode == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                Log.e(TAG, "Ping failed", e)
                false
            }
        }
    }
}
