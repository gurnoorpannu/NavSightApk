package org.tensorflow.lite.examples.objectdetection.navigation

import android.util.Log
import kotlin.math.abs

/**
 * Engine for making navigation decisions based on obstacle angle and distance
 * 
 * @deprecated Replaced by partition-based decision logic in PartitionNavGuidance.
 * The angle-based approach was not reliable in real scenes. This class is kept
 * for backwards compatibility only.
 */
@Deprecated(
    message = "Use PartitionNavGuidance instead - angle-based decisions are unreliable",
    replaceWith = ReplaceWith("PartitionNavGuidance")
)
class NavigationDecisionEngine {
    
    companion object {
        private const val TAG = "NavDecisionEngine"
    }
    
    private var previousDecision: NavigationDecision? = null
    private var previousAngle: Float? = null
    
    /**
     * Decide navigation action based on angle and distance
     * 
     * @param angle Horizontal angle in degrees (negative = left, positive = right)
     * @param distance Distance to obstacle in meters
     * @return Navigation decision
     */
    fun decide(angle: Float, distance: Float): NavigationDecision {
        val decision = when {
            // STOP: Very close obstacle in center
            distance <= NavigationConfig.STOP_DISTANCE_THRESHOLD && 
            abs(angle) <= NavigationConfig.STOP_ANGLE_THRESHOLD -> {
                Log.d(TAG, "STOP: distance=${distance}m <= ${NavigationConfig.STOP_DISTANCE_THRESHOLD}m, " +
                        "angle=${angle}° <= ${NavigationConfig.STOP_ANGLE_THRESHOLD}°")
                NavigationDecision.STOP
            }
            
            // Turn left: Large angle to the left
            angle < NavigationConfig.ANGLE_TURN_LEFT -> {
                Log.d(TAG, "TURN_LEFT: angle=${angle}° < ${NavigationConfig.ANGLE_TURN_LEFT}°")
                NavigationDecision.TURN_LEFT
            }
            
            // Slightly left: Moderate angle to the left
            angle < NavigationConfig.ANGLE_SLIGHTLY_LEFT -> {
                Log.d(TAG, "SLIGHTLY_LEFT: angle=${angle}° < ${NavigationConfig.ANGLE_SLIGHTLY_LEFT}°")
                NavigationDecision.SLIGHTLY_LEFT
            }
            
            // Go straight: Small angle (roughly centered)
            angle >= NavigationConfig.ANGLE_STRAIGHT_MIN && 
            angle <= NavigationConfig.ANGLE_STRAIGHT_MAX -> {
                Log.d(TAG, "GO_STRAIGHT: ${NavigationConfig.ANGLE_STRAIGHT_MIN}° <= angle=${angle}° <= ${NavigationConfig.ANGLE_STRAIGHT_MAX}°")
                NavigationDecision.GO_STRAIGHT
            }
            
            // Slightly right: Moderate angle to the right
            angle <= NavigationConfig.ANGLE_TURN_RIGHT -> {
                Log.d(TAG, "SLIGHTLY_RIGHT: angle=${angle}° <= ${NavigationConfig.ANGLE_TURN_RIGHT}°")
                NavigationDecision.SLIGHTLY_RIGHT
            }
            
            // Turn right: Large angle to the right
            else -> {
                Log.d(TAG, "TURN_RIGHT: angle=${angle}° > ${NavigationConfig.ANGLE_TURN_RIGHT}°")
                NavigationDecision.TURN_RIGHT
            }
        }
        
        return decision
    }
    
    /**
     * Apply hysteresis to prevent oscillation between decisions
     * 
     * @param newDecision The new decision from decide()
     * @param newAngle The current angle
     * @return Decision after applying hysteresis (may be previous decision if angle change is small)
     */
    fun applyHysteresis(newDecision: NavigationDecision, newAngle: Float): NavigationDecision {
        val prevDecision = previousDecision
        val prevAngle = previousAngle
        
        // Update stored values
        previousDecision = newDecision
        previousAngle = newAngle
        
        // If no previous decision, use new decision
        if (prevDecision == null || prevAngle == null) {
            return newDecision
        }
        
        // If decisions are the same, no hysteresis needed
        if (newDecision == prevDecision) {
            return newDecision
        }
        
        // Calculate angle delta
        val angleDelta = abs(newAngle - prevAngle)
        
        // If angle change is small, maintain previous decision
        if (angleDelta < NavigationConfig.ANGLE_HYSTERESIS) {
            Log.d(TAG, "Hysteresis: Angle delta ${angleDelta}° < ${NavigationConfig.ANGLE_HYSTERESIS}°, " +
                    "maintaining $prevDecision (new would be $newDecision)")
            return prevDecision
        }
        
        // Angle change is significant, accept new decision
        Log.d(TAG, "Hysteresis: Angle delta ${angleDelta}° >= ${NavigationConfig.ANGLE_HYSTERESIS}°, " +
                "accepting new decision $newDecision (was $prevDecision)")
        return newDecision
    }
    
    /**
     * Reset the engine state
     */
    fun reset() {
        previousDecision = null
        previousAngle = null
    }
}
