package org.tensorflow.lite.examples.objectdetection

import android.content.Context
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.util.Log
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale

/**
 * SceneAnalyzer - Manages scene analysis with Gemini API
 * 
 * Features:
 * - Cooldown mechanism (prevents API spam)
 * - Scene change detection
 * - Text-to-Speech integration
 * - Fallback descriptions
 * - Production-ready error handling
 */
class SceneAnalyzer(
    context: Context,
    apiKey: String
) {
    companion object {
        private const val TAG = "SceneAnalyzer"
        private const val COOLDOWN_MS = 3500L // 3.5 seconds between API calls
        private const val SCENE_CHANGE_THRESHOLD = 0.4f // 40% change triggers new analysis
        
        // Optimized prompt for visually impaired users
        private const val VISION_ASSIST_PROMPT = """You are assisting a visually impaired user.
Describe the scene in simple, short sentences.
Focus on obstacles, people, directions, and distances.
Keep it under 3 sentences."""
    }
    
    private val geminiClient = GeminiClient(apiKey)
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    
    // Cooldown tracking
    private var lastGeminiCallTime = 0L
    private var lastDetectionSignature = ""
    
    init {
        initializeTextToSpeech(context)
    }
    
    /**
     * Initializes Text-to-Speech engine
     */
    private fun initializeTextToSpeech(context: Context) {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                
                if (isTtsReady) {
                    // Configure TTS for real-time feedback
                    textToSpeech?.setSpeechRate(1.1f) // Slightly faster
                    textToSpeech?.setPitch(1.0f)
                    Log.d(TAG, "Text-to-Speech initialized")
                }
            }
        }
    }
    
    /**
     * Analyzes scene with intelligent cooldown
     * 
     * Calls Gemini API only if:
     * 1. Cooldown period has passed, AND
     * 2. Scene has changed significantly
     * 
     * @param bitmap Current camera frame
     * @param detections YOLO detection results
     */
    fun analyzeScene(bitmap: Bitmap, detections: List<Detection>) {
        val currentTime = System.currentTimeMillis()
        
        // Check cooldown
        if (currentTime - lastGeminiCallTime < COOLDOWN_MS) {
            Log.d(TAG, "Cooldown active, skipping analysis")
            return
        }
        
        // Check scene change
        val currentSignature = generateDetectionSignature(detections)
        if (!hasSceneChanged(currentSignature)) {
            Log.d(TAG, "Scene unchanged, skipping analysis")
            return
        }
        
        // Update tracking
        lastGeminiCallTime = currentTime
        lastDetectionSignature = currentSignature
        
        // Call Gemini API
        Log.d(TAG, "Analyzing scene with Gemini...")
        geminiClient.analyzeImage(bitmap, VISION_ASSIST_PROMPT) { description ->
            handleGeminiResponse(description, detections)
        }
    }
    
    /**
     * MANUAL ANALYSIS: Analyzes surroundings on user request
     * 
     * Bypasses cooldown and scene change detection.
     * Provides comprehensive scene description.
     * 
     * @param bitmap Current camera frame
     * @param detections YOLO detection results (optional, for fallback)
     * @param onComplete Callback when analysis completes (optional)
     */
    fun analyzeSurroundingsManually(
        bitmap: Bitmap, 
        detections: List<Detection>? = null,
        onComplete: (() -> Unit)? = null
    ) {
        Log.d(TAG, "🔍 Manual surroundings analysis requested")
        
        // Update tracking to prevent auto-analysis spam after manual request
        lastGeminiCallTime = System.currentTimeMillis()
        if (detections != null) {
            lastDetectionSignature = generateDetectionSignature(detections)
        }
        
        // Enhanced prompt for comprehensive surroundings analysis
        val comprehensivePrompt = """You are assisting a visually impaired person who wants to understand their surroundings.

Provide a detailed but concise description of the scene including:
1. The type of environment (indoor/outdoor, room type, etc.)
2. Major objects and their locations (left, right, ahead, behind)
3. People present and their approximate positions
4. Any potential obstacles or hazards
5. Overall spatial layout

Keep the description clear, organized, and under 5 sentences.
Use simple directional language (left, right, ahead, behind)."""
        
        // Call Gemini API with comprehensive prompt
        geminiClient.analyzeImage(bitmap, comprehensivePrompt) { description ->
            Log.d(TAG, "📝 Manual analysis result: $description")
            
            // Use Gemini description if valid, otherwise fallback
            val finalDescription = if (description.startsWith("Scene unclear")) {
                if (detections != null && detections.isNotEmpty()) {
                    "Manual analysis: " + buildFallbackDescription(detections)
                } else {
                    "Unable to analyze surroundings. Please try again."
                }
            } else {
                description
            }
            
            // Speak comprehensive description
            speak(finalDescription)
            
            // Notify completion
            onComplete?.invoke()
        }
    }
    
    /**
     * Generates signature from detections for change detection
     * 
     * Format: "person:0.95,car:0.87,chair:0.76"
     */
    private fun generateDetectionSignature(detections: List<Detection>): String {
        return detections
            .sortedByDescending { it.categories[0].score }
            .take(5) // Top 5 objects
            .joinToString(",") { detection ->
                val label = detection.categories[0].label
                val score = String.format("%.2f", detection.categories[0].score)
                "$label:$score"
            }
    }
    
    /**
     * Checks if scene has changed significantly
     * 
     * Uses Jaccard similarity on detection labels
     */
    private fun hasSceneChanged(newSignature: String): Boolean {
        if (lastDetectionSignature.isEmpty()) return true
        
        val oldLabels = lastDetectionSignature.split(",").map { it.split(":")[0] }.toSet()
        val newLabels = newSignature.split(",").map { it.split(":")[0] }.toSet()
        
        if (oldLabels.isEmpty() && newLabels.isEmpty()) return false
        if (oldLabels.isEmpty() || newLabels.isEmpty()) return true
        
        // Calculate Jaccard similarity
        val intersection = oldLabels.intersect(newLabels).size
        val union = oldLabels.union(newLabels).size
        val similarity = intersection.toFloat() / union.toFloat()
        
        return similarity < (1.0f - SCENE_CHANGE_THRESHOLD)
    }
    
    /**
     * Handles Gemini API response
     * 
     * @param description Scene description from Gemini
     * @param detections Fallback detection data
     */
    private fun handleGeminiResponse(description: String, detections: List<Detection>) {
        Log.d(TAG, "Gemini response: $description")
        
        // Use Gemini description if valid, otherwise fallback
        val finalDescription = if (description.startsWith("Scene unclear")) {
            buildFallbackDescription(detections)
        } else {
            description
        }
        
        // Speak description
        speak(finalDescription)
    }
    
    /**
     * Builds fallback description from YOLO detections
     * Used when Gemini API fails
     */
    private fun buildFallbackDescription(detections: List<Detection>): String {
        if (detections.isEmpty()) {
            return "No objects detected"
        }
        
        // Group by position
        val positions = categorizeByPosition(detections)
        val parts = mutableListOf<String>()
        
        // Center (most important)
        positions["center"]?.firstOrNull()?.let {
            parts.add("Ahead: ${it.categories[0].label}")
        }
        
        // Left
        positions["left"]?.let { leftObjects ->
            if (leftObjects.isNotEmpty()) {
                val labels = leftObjects.take(2).joinToString(", ") { it.categories[0].label }
                parts.add("Left: $labels")
            }
        }
        
        // Right
        positions["right"]?.let { rightObjects ->
            if (rightObjects.isNotEmpty()) {
                val labels = rightObjects.take(2).joinToString(", ") { it.categories[0].label }
                parts.add("Right: $labels")
            }
        }
        
        return parts.joinToString(". ")
    }
    
    /**
     * Categorizes detections by screen position
     */
    private fun categorizeByPosition(detections: List<Detection>): Map<String, List<Detection>> {
        val result = mutableMapOf<String, MutableList<Detection>>()
        
        detections.forEach { detection ->
            val centerX = detection.boundingBox.centerX()
            val boundingBoxWidth = detection.boundingBox.width()
            
            // Estimate image width (bounding box is relative)
            val estimatedImageWidth = boundingBoxWidth * 3
            
            val position = when {
                centerX < estimatedImageWidth * 0.33f -> "left"
                centerX > estimatedImageWidth * 0.67f -> "right"
                else -> "center"
            }
            
            result.getOrPut(position) { mutableListOf() }.add(detection)
        }
        
        return result
    }
    
    /**
     * Speaks text using TTS
     * Flushes queue for real-time feedback
     */
    fun speak(text: String) {
        if (isTtsReady && textToSpeech != null) {
            // QUEUE_FLUSH ensures immediate feedback
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "scene_description")
            Log.d(TAG, "Speaking: $text")
        } else {
            Log.w(TAG, "TTS not ready, cannot speak")
        }
    }
    
    /**
     * Cleanup resources
     */
    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        geminiClient.shutdown()
    }
}
