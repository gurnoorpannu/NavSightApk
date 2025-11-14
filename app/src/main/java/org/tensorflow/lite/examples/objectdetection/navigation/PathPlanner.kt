package org.tensorflow.lite.examples.objectdetection.navigation

import android.util.Log

/**
 * PathPlanner - Intelligent path decision engine
 * 
 * Analyzes all obstacles in the scene and provides a single navigation decision:
 * - MOVE_STRAIGHT: Path is clear, continue forward
 * - MOVE_LEFT: Obstacle ahead, shift left
 * - MOVE_RIGHT: Obstacle ahead, shift right
 * - STOP: Dangerous obstacle very close, stop immediately
 */
class PathPlanner {
    
    companion object {
        private const val TAG = "PathPlanner"
        
        // STEP 4: Stricter distance thresholds for better far object suppression
        private const val VERY_CLOSE_WIDTH = 0.45f
        private const val CLOSE_WIDTH = 0.30f
        private const val MEDIUM_WIDTH = 0.20f      // Increased from 0.15f
        private const val FAR_THRESHOLD = 0.12f     // NEW: Hard cutoff for far objects
        
        // Direction boundaries
        private const val LEFT_BOUNDARY = 0.33f
        private const val RIGHT_BOUNDARY = 0.66f
        
        // Minimum confidence for path planning
        private const val MIN_CONFIDENCE = 0.40f
    }
    
    /**
     * Analyzes detections and decides the best path forward
     * 
     * @param detections List of normalized navigation detections
     * @return PathDecision indicating the recommended action
     */
    fun decide(detections: List<NavigationDetection>): PathDecision {
        // Filter valid detections
        val validDetections = detections.filter { 
            it.confidence >= MIN_CONFIDENCE && 
            it.width >= MEDIUM_WIDTH && // Ignore FAR objects
            it.yCenter >= 0.5f // Only lower half (path ahead)
        }
        
        if (validDetections.isEmpty()) {
            return PathDecision.MOVE_STRAIGHT
        }
        
        // Group by direction
        val leftObstacles = validDetections.filter { it.xCenter < LEFT_BOUNDARY }
        val centerObstacles = validDetections.filter { it.xCenter >= LEFT_BOUNDARY && it.xCenter <= RIGHT_BOUNDARY }
        val rightObstacles = validDetections.filter { it.xCenter > RIGHT_BOUNDARY }
        
        // Check for VERY_CLOSE obstacles (immediate danger)
        val veryCloseCenter = centerObstacles.any { it.width > VERY_CLOSE_WIDTH }
        val veryCloseLeft = leftObstacles.any { it.width > VERY_CLOSE_WIDTH }
        val veryCloseRight = rightObstacles.any { it.width > VERY_CLOSE_WIDTH }
        
        // Check for CLOSE obstacles
        val closeCenter = centerObstacles.any { it.width > CLOSE_WIDTH }
        val closeLeft = leftObstacles.any { it.width > CLOSE_WIDTH }
        val closeRight = rightObstacles.any { it.width > CLOSE_WIDTH }
        
        // Decision logic
        val decision = when {
            // STOP: Very close obstacle in center
            veryCloseCenter -> {
                Log.d(TAG, "STOP: Very close obstacle in center")
                PathDecision.STOP
            }
            
            // STOP: Multiple very close obstacles
            (veryCloseLeft && veryCloseRight) -> {
                Log.d(TAG, "STOP: Very close obstacles on both sides")
                PathDecision.STOP
            }
            
            // MOVE_LEFT: Center blocked, right has obstacles, left is clearer
            closeCenter && closeRight && !closeLeft -> {
                Log.d(TAG, "MOVE_LEFT: Center and right blocked, left clear")
                PathDecision.MOVE_LEFT
            }
            
            // MOVE_RIGHT: Center blocked, left has obstacles, right is clearer
            closeCenter && closeLeft && !closeRight -> {
                Log.d(TAG, "MOVE_RIGHT: Center and left blocked, right clear")
                PathDecision.MOVE_RIGHT
            }
            
            // MOVE_LEFT: Center blocked, left has fewer obstacles
            closeCenter && leftObstacles.size < rightObstacles.size -> {
                Log.d(TAG, "MOVE_LEFT: Center blocked, left has fewer obstacles (${leftObstacles.size} vs ${rightObstacles.size})")
                PathDecision.MOVE_LEFT
            }
            
            // MOVE_RIGHT: Center blocked, right has fewer obstacles
            closeCenter && rightObstacles.size <= leftObstacles.size -> {
                Log.d(TAG, "MOVE_RIGHT: Center blocked, right has fewer obstacles (${rightObstacles.size} vs ${leftObstacles.size})")
                PathDecision.MOVE_RIGHT
            }
            
            // MOVE_RIGHT: Left blocked but center clear
            closeLeft && !closeCenter -> {
                Log.d(TAG, "MOVE_RIGHT: Left blocked, center clear")
                PathDecision.MOVE_RIGHT
            }
            
            // MOVE_LEFT: Right blocked but center clear
            closeRight && !closeCenter -> {
                Log.d(TAG, "MOVE_LEFT: Right blocked, center clear")
                PathDecision.MOVE_LEFT
            }
            
            // MOVE_STRAIGHT: Path is clear
            else -> {
                Log.d(TAG, "MOVE_STRAIGHT: Path clear (${validDetections.size} distant obstacles)")
                PathDecision.MOVE_STRAIGHT
            }
        }
        
        return decision
    }
}

/**
 * Path decision enum representing navigation actions
 */
enum class PathDecision {
    MOVE_STRAIGHT,
    MOVE_LEFT,
    MOVE_RIGHT,
    STOP
}
