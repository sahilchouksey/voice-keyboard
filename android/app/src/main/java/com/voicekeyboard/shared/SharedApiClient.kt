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
    
    data class TranscriptionResult(
        val success: Boolean,
        val text: String?,
        val error: String?
    )
    
    /**
     * Set the base URL for the API
     */
    fun setBaseUrl(url: String) {
        baseUrl = url.trimEnd('/')
    }
    
    /**
     * Get the current base URL
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
                    
                    TranscriptionResult(
                        success = false,
                        text = null,
                        error = "HTTP $responseCode: ${errorResponse ?: "Unknown error"}"
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
