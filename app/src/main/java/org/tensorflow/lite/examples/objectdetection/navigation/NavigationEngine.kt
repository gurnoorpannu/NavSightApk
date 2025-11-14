package org.tensorflow.lite.examples.objectdetection.navigation

import android.util.Log
import org.tensorflow.lite.examples.objectdetection.DepthConfig
import kotlin.math.pow

/**
 * Navigation Intelligence Engine
 * 
 * Converts raw YOLO detections into actionable navigation guidance.
 * Handles filtering, direction detection, distance estimation, and priority ranking.
 * 
 * Now supports MiDaS depth-based distance estimation for improved accuracy.
 */
class NavigationEngine {

    companion object {
        private const val TAG = "NavigationEngine"
        
        // Filtering thresholds
        private const val MIN_CONFIDENCE = 0.40f
        private const val MIN_Y_CENTER = 0.5f  // Only lower half of frame
        private const val MIN_WIDTH = 0.05f    // Ignore tiny objects
        
        // Direction boundaries (normalized x-coordinate)
        private const val LEFT_BOUNDARY = 0.33f
        private const val RIGHT_BOUNDARY = 0.66f
        
        // Priority weights
        private const val CONFIDENCE_WEIGHT = 2.0f
        private const val DISTANCE_WEIGHT = 3.0f
        private const val CENTER_DIRECTION_WEIGHT = 4.0f
        private const val SIDE_DIRECTION_WEIGHT = 1.0f
        
        // Stoplist - objects to ignore
        private val STOPLIST = setOf(
            "book", "bottle", "cup", "keyboard", "mouse", 
            "laptop", "charger", "cell phone", "remote"
        )
    }

    /**
     * Main analysis function - processes detections and returns the highest priority guidance.
     * Now includes rate limiting to prevent alert spam.
     * 
     * @param detections List of normalized detections from YOLO
     * @param enableRateLimiting Whether to apply cooldown/anti-spam logic (default: true)
     * @return Guidance object for the most important obstacle, or null if nothing relevant or suppressed
     */
    fun analyzeDetections(
        detections: List<NavigationDetection>,
        enableRateLimiting: Boolean = true
    ): Guidance? {
        // Step 1: Filter detections
        val filtered = detections.filter { shouldIncludeDetection(it) }
        
        if (filtered.isEmpty()) {
            return null
        }
        
        // Step 2: Convert to guidance candidates with priority scores
        // Keep track of original detection for rate limiter
        val candidatesWithDetection = filtered.map { detection ->
            val direction = calculateDirection(detection.xCenter)
            val distance = calculateDistance(detection)
            val priority = calculatePriority(detection, direction, distance)
            
            val guidance = Guidance(
                label = detection.label,
                direction = direction,
                distance = distance,
                priority = priority
            )
            
            Pair(guidance, detection)
        }
        
        // Step 3: Get highest priority guidance
        val topCandidate = candidatesWithDetection.maxByOrNull { it.first.priority }
            ?: return null
        
        val (guidance, detection) = topCandidate
        
        // Step 4: Apply rate limiting if enabled
        if (enableRateLimiting) {
            val shouldAnnounce = WarningRateLimiter.shouldAnnounce(
                guidance,
                currentWidth = detection.width,
                currentXCenter = detection.xCenter
            )
            
            if (!shouldAnnounce) {
                return null
            }
            
            // Record the announcement
            WarningRateLimiter.recordAnnouncement(guidance)
        }
        
        return guidance
    }

    /**
     * Filter logic - determines if a detection should be considered.
     */
    private fun shouldIncludeDetection(detection: NavigationDetection): Boolean {
        // Check confidence threshold
        if (detection.confidence < MIN_CONFIDENCE) {
            return false
        }
        
        // Only consider objects in lower half of frame (path ahead)
        if (detection.yCenter < MIN_Y_CENTER) {
            return false
        }
        
        // Ignore tiny objects
        if (detection.width < MIN_WIDTH) {
            return false
        }
        
        // Check stoplist
        val normalizedLabel = detection.label.lowercase().trim()
        if (STOPLIST.any { normalizedLabel.contains(it) }) {
            return false
        }
        
        return true
    }

    /**
     * Calculate direction based on horizontal center position.
     */
    private fun calculateDirection(xCenter: Float): Direction {
        return when {
            xCenter < LEFT_BOUNDARY -> Direction.LEFT
            xCenter > RIGHT_BOUNDARY -> Direction.RIGHT
            else -> Direction.CENTER
        }
    }

    /**
     * Calculate distance category using depth information.
     * Uses MiDaS depth measurements for accurate distance estimation.
     * 
     * @param detection NavigationDetection with depth information
     * @return DistanceCategory based on depth measurements
     */
    private fun calculateDistance(detection: NavigationDetection): DistanceCategory {
        // Use depth-based distance
        val distanceMeters = detection.distanceMeters
        
        return if (distanceMeters != null) {
            // Use MiDaS depth measurements (accurate)
            Log.d(TAG, "Depth-based distance for ${detection.label}: ${distanceMeters}m (depth=${detection.depthValue})")
            calculateDistanceFromMeters(distanceMeters)
        } else {
            // No depth available - log warning and use FAR as safe default
            Log.w(TAG, "No depth available for ${detection.label}, defaulting to FAR")
            DistanceCategory.FAR
        }
    }
    
    /**
     * Calculate distance category from depth measurements in meters.
     * Uses MiDaS depth estimation for accurate distance categorization.
     */
    private fun calculateDistanceFromMeters(meters: Float): DistanceCategory {
        return when {
            meters < DepthConfig.DEPTH_VERY_CLOSE_THRESHOLD -> DistanceCategory.VERY_CLOSE
            meters < DepthConfig.DEPTH_CLOSE_THRESHOLD -> DistanceCategory.CLOSE
            meters < DepthConfig.DEPTH_MEDIUM_THRESHOLD -> DistanceCategory.MEDIUM
            else -> DistanceCategory.FAR
        }
    }

    /**
     * Calculate priority score for ranking detections.
     * Higher score = more important to announce.
     * 
     * Uses MiDaS depth measurements exclusively for accurate prioritization.
     * Formula: priority = confidence*2 + distanceContribution*3 + directionWeight
     */
    private fun calculatePriority(
        detection: NavigationDetection,
        direction: Direction,
        distance: DistanceCategory
    ): Float {
        // Base: confidence contribution
        var priority = detection.confidence * CONFIDENCE_WEIGHT
        
        // Distance contribution (closer = higher priority)
        val distanceContribution = if (detection.distanceMeters != null) {
            // Use accurate depth-based distance
            val meters = detection.distanceMeters
            // Inverse relationship: closer objects get higher scores
            // Cap at 10m to prevent extreme values
            val cappedMeters = meters.coerceIn(0.1f, 10.0f)
            (10.0f / cappedMeters) * DISTANCE_WEIGHT
        } else {
            // No depth available - use low priority
            Log.w(TAG, "No depth for priority calculation: ${detection.label}")
            0.5f * DISTANCE_WEIGHT
        }
        priority += distanceContribution
        
        // Direction contribution (center is more important)
        val directionWeight = when (direction) {
            Direction.CENTER -> CENTER_DIRECTION_WEIGHT
            Direction.LEFT, Direction.RIGHT -> SIDE_DIRECTION_WEIGHT
        }
        priority += directionWeight
        
        return priority
    }
}
