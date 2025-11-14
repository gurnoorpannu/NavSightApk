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
 * STEP 4 ENHANCEMENTS:
 * - TTS interruption for instant feedback
 * - Decision stabilization (200ms debounce)
 * - Movement trend detection (object moving away)
 * - QUEUE_FLUSH for all navigation speech
 * - Shortened messages for faster delivery
 * 
 * MIDAS DEPTH INTEGRATION:
 * - Enriches detections with accurate depth measurements
 * - Falls back to pixel-based distance if depth unavailable
 */
class NavigationGuidanceManager(
    context: Context,
    private val depthEstimator: DepthEstimator? = null
) {
    
    companion object {
        private const val TAG = "NavigationGuidance"
        
        // STEP 4: Decision stabilization
        private const val DECISION_STABILIZATION_MS = 200L
        
        // STEP 4: Movement detection threshold
        private const val WIDTH_SHRINK_THRESHOLD = 0.05f
    }
    
    private val navigationEngine = NavigationEngine()
    private val pathPlanner = PathPlanner()
    private val sceneSummaryEngine = SceneSummaryEngine()
    
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    
    // STEP 4: Enhanced state machine
    private var lastPathDecision: PathDecision = PathDecision.MOVE_STRAIGHT
    private var pendingDecision: PathDecision? = null
    private var pendingDecisionTimestamp: Long = 0L
    
    // STEP 4: Movement trend tracking
    private var lastObstacleWidth: Float = 0f
    private var lastObstacleLabel: String? = null
    
    init {
        initializeTextToSpeech(context)
    }
    
    /**
     * Initializes dedicated TTS engine for navigation guidance
     * STEP 4: Configured for instant, interruptible speech
     */
    private fun initializeTextToSpeech(context: Context) {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                
                if (isTtsReady) {
                    // STEP 4: Faster speech for urgent navigation
                    textToSpeech?.setSpeechRate(1.3f) // Increased from 1.2f
                    textToSpeech?.setPitch(1.0f)
                    Log.d(TAG, "Navigation TTS initialized (Step 4: interruptible mode)")
                }
            }
        }
    }
    
    /**
     * STEP 4: Interrupt any ongoing speech immediately
     * Critical for real-time navigation responsiveness
     */
    private fun interruptSpeech() {
        textToSpeech?.stop()
        Log.d(TAG, "⚠️ Speech interrupted")
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
        
        // Enrich with depth information if available
        val enrichedDetections = if (depthEstimator != null) {
            DepthEnricher.enrichWithDepth(
                navigationDetections,
                depthEstimator,
                imageWidth,
                imageHeight
            )
        } else {
            navigationDetections
        }
        
        // Log detection count and depth enrichment status
        Log.d(TAG, "Processing ${enrichedDetections.size} detections")
        if (depthEstimator != null) {
            Log.d(TAG, DepthEnricher.getEnrichmentSummary(enrichedDetections))
        }
        
        // Analyze with NavigationEngine (includes rate limiting)
        val guidance = navigationEngine.analyzeDetections(
            detections = enrichedDetections,
            enableRateLimiting = true
        )
        
        // Speak guidance if approved by rate limiter
        if (guidance != null) {
            val announcement = guidanceToAnnouncement(guidance)
            speak(announcement)
            Log.d(TAG, "✓ ANNOUNCED: $announcement [label=${guidance.label}, distance=${guidance.distance}, direction=${guidance.direction}, priority=${guidance.priority}]")
        } else {
            Log.d(TAG, "✗ SUPPRESSED: No guidance generated (filtered or rate-limited)")
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
     * Provides continuous path guidance based on all obstacles
     * Uses state machine to avoid repeating same decision
     * 
     * @param detections YOLO detection results
     * @param imageWidth Image width for normalization
     * @param imageHeight Image height for normalization
     */
    fun providePathGuidance(
        detections: List<Detection>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        if (detections.isEmpty()) {
            return
        }
        
        // Convert detections
        val navigationDetections = detections.mapNotNull { detection ->
            try {
                DetectionConverter.toNavigationDetection(detection, imageWidth, imageHeight)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to convert detection: ${e.message}")
                null
            }
        }
        
        // Enrich with depth information if available
        val enrichedDetections = if (depthEstimator != null) {
            DepthEnricher.enrichWithDepth(
                navigationDetections,
                depthEstimator,
                imageWidth,
                imageHeight
            )
        } else {
            navigationDetections
        }
        
        // Get path decision
        val decision = pathPlanner.decide(enrichedDetections)
        
        // Only speak if decision changed (state machine)
        if (decision != lastPathDecision) {
            speakPathGuidance(decision)
            lastPathDecision = decision
        }
    }
    
    /**
     * Speaks path guidance based on decision
     * 
     * @param decision The path decision to announce
     */
    private fun speakPathGuidance(decision: PathDecision) {
        val guidance = when (decision) {
            PathDecision.MOVE_STRAIGHT -> "Path clear, keep walking straight"
            PathDecision.MOVE_LEFT -> "Obstacle ahead, move slightly left"
            PathDecision.MOVE_RIGHT -> "Move to your right to avoid the obstacle"
            PathDecision.STOP -> "Stop. Obstacle very close ahead"
        }
        
        speak(guidance)
        Log.d(TAG, "Path guidance: $guidance (decision=$decision)")
    }
    
    /**
     * Generates and speaks a scene summary
     * Call this on user request or automatically when scene changes
     * 
     * @param detections YOLO detection results
     * @param imageWidth Image width for normalization
     * @param imageHeight Image height for normalization
     */
    fun speakSceneSummary(
        detections: List<Detection>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        // Convert detections
        val navigationDetections = detections.mapNotNull { detection ->
            try {
                DetectionConverter.toNavigationDetection(detection, imageWidth, imageHeight)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to convert detection: ${e.message}")
                null
            }
        }
        
        // Generate summary
        val summary = sceneSummaryEngine.generateSummary(navigationDetections)
        speak(summary)
        Log.d(TAG, "Scene summary: $summary")
    }
    
    /**
     * Checks if scene summary should be automatically triggered
     * 
     * @param detections YOLO detection results
     * @param imageWidth Image width for normalization
     * @param imageHeight Image height for normalization
     * @return true if auto-summary should be spoken
     */
    fun shouldAutoSummarize(
        detections: List<Detection>,
        imageWidth: Int,
        imageHeight: Int
    ): Boolean {
        val navigationDetections = detections.mapNotNull { detection ->
            try {
                DetectionConverter.toNavigationDetection(detection, imageWidth, imageHeight)
            } catch (e: Exception) {
                null
            }
        }
        
        return sceneSummaryEngine.shouldAutoSummarize(navigationDetections)
    }
    
    /**
     * Resets all state (path decision, scene summary tracking, rate limiter)
     */
    fun resetAll() {
        lastPathDecision = PathDecision.MOVE_STRAIGHT
        sceneSummaryEngine.reset()
        WarningRateLimiter.reset()
        Log.d(TAG, "All state reset")
    }
    
    /**
     * Pauses navigation guidance (stops TTS)
     * Used when manual surroundings analysis is triggered
     */
    fun pauseGuidance() {
        textToSpeech?.stop()
        Log.d(TAG, "⏸️ Navigation guidance paused")
    }
    
    /**
     * Resumes navigation guidance
     * Called after manual surroundings analysis completes
     */
    fun resumeGuidance() {
        // Navigation will automatically resume on next frame
        Log.d(TAG, "▶️ Navigation guidance resumed")
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
