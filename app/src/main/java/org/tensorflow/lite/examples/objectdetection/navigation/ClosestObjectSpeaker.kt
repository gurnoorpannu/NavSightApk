package org.tensorflow.lite.examples.objectdetection.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import kotlin.math.abs

/**
 * ClosestObjectSpeaker - Speaks ONLY the single closest object using MiDaS depth
 * 
 * BEHAVIOR:
 * - Evaluates ALL detected objects after depth enrichment
 * - Selects ONLY the SINGLE CLOSEST object by distanceMeters
 * - Uses EMA smoothing (alpha=0.35) to avoid jitter
 * - Hysteresis: Only speaks if distance changes >0.3m OR label changes
 * - Cooldown: Maximum once every 1200ms
 * - Format: "[label], about X.X meters to your left/right/ahead"
 */
class ClosestObjectSpeaker(context: Context) {
    
    companion object {
        private const val TAG = "ClosestObjectSpeaker"
        
        // Filtering
        private const val MIN_CONFIDENCE = 0.40f
        
        // EMA smoothing
        private const val EMA_ALPHA = 0.35f
        
        // Hysteresis
        private const val DISTANCE_CHANGE_THRESHOLD = 0.3f // meters
        
        // Cooldown
        private const val COOLDOWN_MS = 1200L
    }
    
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    
    // State tracking
    private var lastSpokenLabel: String? = null
    private var lastSpokenDistance: Float? = null
    private var smoothedDistance: Float? = null
    private var lastSpeechTimestamp: Long = 0L
    
    init {
        initializeTextToSpeech(context)
    }
    
    private fun initializeTextToSpeech(context: Context) {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                
                if (isTtsReady) {
                    textToSpeech?.setSpeechRate(1.2f)
                    textToSpeech?.setPitch(1.0f)
                    Log.d(TAG, "ClosestObjectSpeaker TTS initialized")
                }
            }
        }
    }
    
    /**
     * Process detections and speak the closest object
     * 
     * @param detections List of NavigationDetections enriched with depth
     * @param previewWidth Width of preview for left/center/right determination
     */
    fun processDetections(detections: List<NavigationDetection>, previewWidth: Int) {
        // Filter by confidence
        val validDetections = detections.filter { it.confidence >= MIN_CONFIDENCE }
        
        if (validDetections.isEmpty()) {
            return
        }
        
        // Find closest object by distanceMeters
        val closestObject = validDetections
            .filter { it.distanceMeters != null } // Only consider objects with depth
            .minByOrNull { it.distanceMeters!! }
        
        if (closestObject == null) {
            Log.d(TAG, "No objects with depth information")
            return
        }
        
        val rawDistance = closestObject.distanceMeters!!
        val label = closestObject.label
        
        // Apply EMA smoothing
        val currentSmoothed = if (smoothedDistance == null) {
            rawDistance
        } else {
            EMA_ALPHA * rawDistance + (1 - EMA_ALPHA) * smoothedDistance!!
        }
        smoothedDistance = currentSmoothed
        
        // Check cooldown
        val now = System.currentTimeMillis()
        if (now - lastSpeechTimestamp < COOLDOWN_MS) {
            Log.d(TAG, "Cooldown active, skipping speech (${COOLDOWN_MS - (now - lastSpeechTimestamp)}ms remaining)")
            return
        }
        
        // Check hysteresis: distance change OR label change
        val distanceChanged = lastSpokenDistance?.let { 
            abs(currentSmoothed - it) > DISTANCE_CHANGE_THRESHOLD 
        } ?: true
        
        val labelChanged = lastSpokenLabel != label
        
        if (!distanceChanged && !labelChanged) {
            Log.d(TAG, "Hysteresis: Same object ($label) at similar distance (${currentSmoothed}m vs ${lastSpokenDistance}m), skipping")
            return
        }
        
        // Log why we're speaking
        if (labelChanged) {
            Log.d(TAG, "Label changed: $lastSpokenLabel → $label")
        }
        if (distanceChanged) {
            Log.d(TAG, "Distance changed: ${lastSpokenDistance}m → ${currentSmoothed}m (Δ=${abs((lastSpokenDistance ?: 0f) - currentSmoothed)}m)")
        }
        
        // Determine direction
        val centerX = closestObject.xCenter
        val direction = when {
            centerX < 1f / 3f -> "to your left"
            centerX > 2f / 3f -> "to your right"
            else -> "ahead"
        }
        
        // Format speech
        val announcement = String.format(
            "%s, about %.1f meters %s",
            label,
            currentSmoothed,
            direction
        )
        
        // Speak
        speak(announcement)
        
        // Update state
        lastSpokenLabel = label
        lastSpokenDistance = currentSmoothed
        lastSpeechTimestamp = now
        
        Log.d(TAG, "✓ CLOSEST: $announcement [raw=${rawDistance}m, smoothed=${currentSmoothed}m]")
    }
    
    private fun speak(text: String) {
        if (isTtsReady && textToSpeech != null) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "closest_object")
        } else {
            Log.w(TAG, "TTS not ready, cannot speak: $text")
        }
    }
    
    /**
     * Reset state (useful when starting new session)
     */
    fun reset() {
        lastSpokenLabel = null
        lastSpokenDistance = null
        smoothedDistance = null
        lastSpeechTimestamp = 0L
        Log.d(TAG, "State reset")
    }
    
    /**
     * Cleanup resources
     */
    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        Log.d(TAG, "ClosestObjectSpeaker shutdown")
    }
}
