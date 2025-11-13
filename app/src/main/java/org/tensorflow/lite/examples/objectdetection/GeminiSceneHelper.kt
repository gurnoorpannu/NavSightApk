package org.tensorflow.lite.examples.objectdetection

import android.content.Context
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.task.vision.detector.Detection
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class GeminiSceneHelper(
    private val context: Context,
    private val apiKey: String
) {
    private val TAG = "GeminiSceneHelper"
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var lastAnnouncementTime = 0L
    private val announcementInterval = 5000L

    init {
        initializeTextToSpeech()
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                if (isTtsReady) {
                    Log.d(TAG, "Text-to-Speech initialized successfully")
                }
            }
        }
    }

    fun analyzeScene(
        bitmap: Bitmap,
        detections: List<Detection>,
        onResult: (String) -> Unit
    ) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnnouncementTime < announcementInterval) {
            return
        }
        lastAnnouncementTime = currentTime

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prompt = buildPrompt(detections)
                val base64Image = bitmapToBase64(bitmap)
                val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", base64Image)
                                    })
                                })
                            })
                        })
                    })
                }

                val response = makeApiCall(apiUrl, requestBody.toString())
                val description = parseResponse(response) ?: "Unable to analyze scene"

                withContext(Dispatchers.Main) {
                    onResult(description)
                    speak(description)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing scene with Gemini", e)
                withContext(Dispatchers.Main) {
                    val fallbackDescription = buildFallbackDescription(detections)
                    onResult(fallbackDescription)
                    speak(fallbackDescription)
                }
            }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun makeApiCall(apiUrl: String, requestBody: String): String {
        val url = URL(apiUrl)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            connection.outputStream.use { os ->
                val input = requestBody.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw Exception("API call failed with response code: $responseCode, error: $errorStream")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(response: String): String? {
        return try {
            val jsonResponse = JSONObject(response)
            val candidates = jsonResponse.getJSONArray("candidates")
            if (candidates.length() > 0) {
                val content = candidates.getJSONObject(0).getJSONObject("content")
                val parts = content.getJSONArray("parts")
                if (parts.length() > 0) {
                    parts.getJSONObject(0).getString("text")
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response", e)
            null
        }
    }

    private fun buildPrompt(detections: List<Detection>): String {
        val detectedObjects = detections.joinToString(", ") {
            "${it.categories[0].label} at ${String.format("%.0f", it.categories[0].score * 100)}% confidence"
        }

        return """You are assisting a blind person navigate their surroundings. 
            |Analyze this image and provide a brief, clear description focusing on:
            |1. The main objects and their approximate positions (left, center, right, near, far)
            |2. Any potential obstacles or hazards
            |3. The general environment (indoor/outdoor, room type, etc.)
            |
            |Detected objects: $detectedObjects
            |
            |Keep the description concise (2-3 sentences) and prioritize safety-relevant information.
            |Start with the most important information first.""".trimMargin()
    }

    private fun buildFallbackDescription(detections: List<Detection>): String {
        if (detections.isEmpty()) {
            return "No objects detected in view"
        }

        val objectsByPosition = categorizeObjectsByPosition(detections)
        val description = StringBuilder()

        objectsByPosition["center"]?.let { centerObjects ->
            if (centerObjects.isNotEmpty()) {
                val obj = centerObjects.first()
                description.append("Ahead: ${obj.categories[0].label}. ")
            }
        }

        objectsByPosition["left"]?.let { leftObjects ->
            if (leftObjects.isNotEmpty()) {
                description.append("Left: ${leftObjects.joinToString(", ") { it.categories[0].label }}. ")
            }
        }

        objectsByPosition["right"]?.let { rightObjects ->
            if (rightObjects.isNotEmpty()) {
                description.append("Right: ${rightObjects.joinToString(", ") { it.categories[0].label }}. ")
            }
        }

        return description.toString().ifEmpty { "Objects detected but position unclear" }
    }

    private fun categorizeObjectsByPosition(detections: List<Detection>): Map<String, List<Detection>> {
        val result = mutableMapOf<String, MutableList<Detection>>()

        detections.forEach { detection ->
            val centerX = detection.boundingBox.centerX()
            val imageWidth = detection.boundingBox.width() * 3

            val position = when {
                centerX < imageWidth * 0.33f -> "left"
                centerX > imageWidth * 0.67f -> "right"
                else -> "center"
            }

            result.getOrPut(position) { mutableListOf() }.add(detection)
        }

        return result
    }

    fun speak(text: String) {
        if (isTtsReady && textToSpeech != null) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            Log.d(TAG, "Speaking: $text")
        } else {
            Log.w(TAG, "Text-to-Speech not ready")
        }
    }

    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
