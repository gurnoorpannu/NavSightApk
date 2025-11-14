package org.tensorflow.lite.examples.objectdetection

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import org.tensorflow.lite.examples.objectdetection.navigation.*
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale

/**
 * NavigationGuidanceManager - Real-time navigation guidance with TTS
 * 
 * Integrates NavigationEngine + WarningRateLimiter to provide:
 * - Immediate obstacle warnings (< 100ms latency)
 * - Anti-spam cooldown logic
 * - Natural language announcements
 * - Independent TTS for navigation (separate from Gemini scene descriptions)
 */
class NavigationGuidanceManager(context: Context) {
    
    companion object {
        private const val TAG = "NavigationGuidance"
    }
    
    private val navigationEngine = NavigationEngine()
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    
    init {
        initializeTextToSpeech(context)
    }
    
    /**
     * Initializes dedicated TTS engine for navigation guidance
     */
    private fun initializeTextToSpeech(context: Context) {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                
                if (isTtsReady) {
                    // Configure for urgent navigation alerts
                    textToSpeech?.setSpeechRate(1.2f) // Faster for urgency
                    textToSpeech?.setPitch(1.0f)
                    Log.d(TAG, "Navigation TTS initialized")
                }
            }
        }
    }
    
    /**
     * Processes detections and provides navigation guidance
     * 
     * Called on every frame from CameraFragment.onResults()
     * Rate limiting handled internally by WarningRateLimiter
     * 
     * @param detections YOLO detection results
     * @param imageWidth Image width for normalization
     * @param imageHeight Image height for normalization
     */
    fun processFrame(
        detections: List<Detection>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        if (detections.isEmpty()) {
            return
        }
        
        // Convert TensorFlow Lite detections to NavigationDetections
        val navigationDetections = detections.mapNotNull { detection ->
            try {
                DetectionConverter.toNavigationDetection(
                    detection,
                    imageWidth,
                    imageHeight
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to convert detection: ${e.message}")
                null
            }
        }
        
        // Analyze with NavigationEngine (includes rate limiting)
        val guidance = navigationEngine.analyzeDetections(
            detections = navigationDetections,
            enableRateLimiting = true
        )
        
        // Speak guidance if approved by rate limiter
        if (guidance != null) {
            val announcement = guidanceToAnnouncement(guidance)
            speak(announcement)
            Log.d(TAG, "Navigation guidance: $announcement")
        }
    }
    
    /**
     * Converts Guidance object to natural language announcement
     * 
     * Examples:
     * - "Person ahead, very close — stop"
     * - "Chair to your left, close — slow down"
     * - "Car to your right, approaching"
     */
    private fun guidanceToAnnouncement(guidance: Guidance): String {
        val directionPhrase = when (guidance.direction) {
            Direction.CENTER -> "ahead"
            Direction.LEFT -> "to your left"
            Direction.RIGHT -> "to your right"
        }
        
        val distancePhrase = when (guidance.distance) {
            DistanceCategory.VERY_CLOSE -> "very close — stop"
            DistanceCategory.CLOSE -> "close — slow down"
            DistanceCategory.MEDIUM -> "approaching"
            DistanceCategory.FAR -> "in the distance"
        }
        
        return "${guidance.label} $directionPhrase, $distancePhrase"
    }
    
    /**
     * Speaks navigation guidance using TTS
     * Uses QUEUE_ADD to avoid interrupting ongoing announcements
     */
    private fun speak(text: String) {
        if (isTtsReady && textToSpeech != null) {
            // QUEUE_ADD allows multiple warnings to queue naturally
            textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, null, "navigation_guidance")
        } else {
            Log.w(TAG, "TTS not ready, cannot speak: $text")
        }
    }
    
    /**
     * Resets rate limiter state
     * Useful when starting a new navigation session
     */
    fun reset() {
        WarningRateLimiter.reset()
        Log.d(TAG, "Rate limiter reset")
    }
    
    /**
     * Gets debug information about rate limiter state
     */
    fun getDebugInfo(): String {
        return WarningRateLimiter.getDebugInfo()
    }
    
    /**
     * Cleanup resources
     */
    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        Log.d(TAG, "Navigation guidance manager shutdown")
    }
}
