package org.tensorflow.lite.examples.objectdetection.navigation

import org.tensorflow.lite.task.vision.detector.Detection

/**
 * Example usage of NavigationEngine for Developer B integration.
 * 
 * This shows how to:
 * 1. Convert TensorFlow Lite detections to NavigationDetections
 * 2. Process them through NavigationEngine with rate limiting
 * 3. Get a single Guidance object for TTS/haptic output (or null if suppressed)
 * 
 * Step 2 Enhancement: Now includes automatic cooldown and anti-spam logic.
 */
object NavigationEngineExample {

    /**
     * Main integration point for Developer B.
     * 
     * Takes raw YOLO detections and returns actionable guidance with rate limiting.
     * Returns null if:
     * - No relevant objects detected
     * - Global cooldown active (2.5s)
     * - Object-specific cooldown active (5s)
     * - No distance category change detected
     * - Object suppressed by hard rules (FAR, edges, etc.)
     * 
     * Developer B can then convert non-null guidance to TTS like:
     * - "Person ahead, very close — stop"
     * - "Chair to your left, close — slow down"
     * 
     * @param detections Raw detections from ObjectDetectorHelper
     * @param imageWidth Width of the analyzed image
     * @param imageHeight Height of the analyzed image
     * @param enableRateLimiting Whether to apply cooldown logic (default: true)
     * @return Guidance object or null if nothing important or suppressed
     */
    fun processFrame(
        detections: List<Detection>,
        imageWidth: Int,
        imageHeight: Int,
        enableRateLimiting: Boolean = true
    ): Guidance? {
        // Step 1: Convert TensorFlow detections to normalized format
        val navigationDetections = DetectionConverter.toNavigationDetections(
            detections,
            imageWidth,
            imageHeight
        )
        
        // Step 2: Analyze and get highest priority guidance (with rate limiting)
        val engine = NavigationEngine()
        return engine.analyzeDetections(navigationDetections, enableRateLimiting)
    }
    
    /**
     * Process frame without rate limiting (for testing or special cases).
     * Returns guidance even if cooldowns would normally suppress it.
     */
    fun processFrameUnlimited(
        detections: List<Detection>,
        imageWidth: Int,
        imageHeight: Int
    ): Guidance? {
        return processFrame(detections, imageWidth, imageHeight, enableRateLimiting = false)
    }

    /**
     * Example of converting Guidance to a human-readable announcement.
     * Developer B can use this as a reference for TTS integration.
     */
    fun guidanceToAnnouncement(guidance: Guidance): String {
        val directionText = when (guidance.direction) {
            Direction.LEFT -> "to your left"
            Direction.RIGHT -> "to your right"
            Direction.CENTER -> "ahead"
        }
        
        val distanceText = when (guidance.distance) {
            DistanceCategory.VERY_CLOSE -> "very close — stop"
            DistanceCategory.CLOSE -> "close — slow down"
            DistanceCategory.MEDIUM -> "approaching"
            DistanceCategory.FAR -> "in the distance"
        }
        
        return "${guidance.label} $directionText, $distanceText"
    }
}
