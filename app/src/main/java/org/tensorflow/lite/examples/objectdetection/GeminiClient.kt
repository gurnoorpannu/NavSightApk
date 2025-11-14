package org.tensorflow.lite.examples.objectdetection

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GeminiClient - Handles communication with Google Gemini 1.5 Flash Vision API
 * 
 * Features:
 * - Secure API key management via BuildConfig
 * - OkHttp for efficient networking
 * - Image compression and Base64 encoding
 * - JSON request/response handling
 * - Timeout and error handling
 * - Callback-based async API
 */
class GeminiClient(private val apiKey: String) {
    
    companion object {
        private const val TAG = "GeminiClient"
        // Fixed: Use v1beta API with correct model name
        private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        private const val TIMEOUT_SECONDS = 15L
        private const val IMAGE_QUALITY = 75 // JPEG quality (0-100)
        private const val MAX_IMAGE_SIZE = 1024 // Max dimension for image
    }
    
    // OkHttp client with timeouts
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()
    
    /**
     * Analyzes an image with Gemini Vision API
     * 
     * @param bitmap The image to analyze
     * @param prompt The text prompt for analysis
     * @param callback Callback with result (success or error message)
     */
    fun analyzeImage(
        bitmap: Bitmap,
        prompt: String,
        callback: (result: String) -> Unit
    ) {
        try {
            // Step 1: Compress and resize image
            val optimizedBitmap = optimizeBitmap(bitmap)
            
            // Step 2: Convert to Base64 JPEG
            val base64Image = bitmapToBase64Jpeg(optimizedBitmap)
            
            // Step 3: Build JSON request body
            val requestBody = buildRequestBody(base64Image, prompt)
            
            // Step 4: Create HTTP request
            val request = Request.Builder()
                .url("$GEMINI_API_URL?key=$apiKey")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            // Step 5: Execute async request
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Network request failed", e)
                    callback("Scene unclear - network error")
                }
                
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            Log.e(TAG, "API error: ${response.code} - ${response.message}")
                            val errorBody = response.body?.string()
                            Log.e(TAG, "Error body: $errorBody")
                            callback("Scene unclear - API error")
                            return
                        }
                        
                        // Step 6: Parse response
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            val description = parseGeminiResponse(responseBody)
                            callback(description ?: "Scene unclear - parsing error")
                        } else {
                            callback("Scene unclear - empty response")
                        }
                    }
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image", e)
            callback("Scene unclear - processing error")
        }
    }
    
    /**
     * Optimizes bitmap for API transmission
     * - Resizes if too large
     * - Maintains aspect ratio
     */
    private fun optimizeBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // Check if resizing needed
        if (width <= MAX_IMAGE_SIZE && height <= MAX_IMAGE_SIZE) {
            return bitmap
        }
        
        // Calculate new dimensions maintaining aspect ratio
        val scale = MAX_IMAGE_SIZE.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Converts bitmap to Base64-encoded JPEG
     * 
     * @param bitmap Input bitmap
     * @return Base64 string
     */
    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    /**
     * Builds Gemini API request body
     * 
     * Format:
     * {
     *   "contents": [{
     *     "parts": [
     *       {"text": "prompt"},
     *       {"inline_data": {"mime_type": "image/jpeg", "data": "base64..."}}
     *     ]
     *   }]
     * }
     */
    private fun buildRequestBody(base64Image: String, prompt: String): String {
        return JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        // Add text prompt first
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                        // Add image data
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
        }.toString()
    }
    
    /**
     * Parses Gemini API response
     * 
     * Expected format:
     * {
     *   "candidates": [{
     *     "content": {
     *       "parts": [{"text": "description"}]
     *     }
     *   }]
     * }
     * 
     * @param responseBody JSON response string
     * @return Extracted text description or null
     */
    private fun parseGeminiResponse(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            
            // Navigate JSON structure
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                
                if (parts != null && parts.length() > 0) {
                    val firstPart = parts.getJSONObject(0)
                    firstPart.optString("text")
                } else null
            } else null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response", e)
            null
        }
    }
    
    /**
     * Cleanup resources
     */
    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
