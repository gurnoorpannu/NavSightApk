package org.tensorflow.lite.examples.objectdetection.navigation

import android.content.Context
import android.util.Log

/**
 * Angle-Based Navigation Guidance Manager
 * 
 * @deprecated Replaced by PartitionNavGuidance which uses a 3-partition screen approach
 * instead of angle-based smoothing. The angle-based approach was not reliable in real
 * scenes and caused repeated/ambiguous instructions. Use PartitionNavGuidance instead.
 * 
 * This class is kept for backwards compatibility only.
 * 
 * Provides turn instructions (Turn left/right, Go straight, Stop) based on obstacle
 * angles and distances using MiDaS depth estimation.
 * 
 * Works in addition to ClosestObjectSpeaker without breaking it.
 */
@Deprecated(
    message = "Use PartitionNavGuidance instead - angle-based approach is unreliable",
    replaceWith = ReplaceWith("PartitionNavGuidance(context, speechCoordinator)")
)
class AngleBasedNavigationGuidance(
    context: Context,
    private val speechCoordinator: SpeechCoordinator
) {
    
    companion object {
        private const val TAG = "AngleNavGuidance"
    }
    
    // Core components
    private val decisionEngine = NavigationDecisionEngine()
    private val angleSmoother = ExponentialSmoother(NavigationConfig.SMOOTHING_ALPHA)
    private val distanceSmoother = ExponentialSmoother(NavigationConfig.SMOOTHING_ALPHA)
    private val stabilityTracker = StabilityTracker(NavigationConfig.STABILITY_FRAMES)
    
    // State tracking
    private var lastSpeechTimestamp: Long = 0L
    private var currentDecision: NavigationDecision? = null
    
    // Delta tracking for gating re-announcements
    private var lastSpokenDistance: Float? = null
    private var lastSpokenAngle: Float? = null
    
    /**
     * Update navigation guidance with new detections
     * 
     * @param detections List of depth-enriched NavigationDetections
     * @param imageWidth Image width for angle calculation
     * @param imageHeight Image height (unused but kept for consistency)
     */
    fun update(
        detections: List<NavigationDetection>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        // Step 1: Filter detections
        val validTargets = detections.filter { detection ->
            detection.confidence >= NavigationConfig.MIN_CONFIDENCE &&
            detection.distanceMeters != null &&
            detection.distanceMeters <= NavigationConfig.NAVIGATION_DISTANCE_THRESHOLD
        }
        
        if (validTargets.isEmpty()) {
            Log.d(TAG, "No obstacles within navigation threshold (${NavigationConfig.NAVIGATION_DISTANCE_THRESHOLD}m)")
            stabilityTracker.reset()
            return
        }
        
        // Step 2: Select closest obstacle as target
        val target = validTargets.minByOrNull { it.distanceMeters!! }!!
        
        val rawDistance = target.distanceMeters!!
        val rawAngle = AngleCalculator.calculateAngle(
            AngleCalculator.getNormalizedCenterX(target),
            NavigationConfig.HORIZONTAL_FOV_DEGREES
        )
        
        Log.d(TAG, "Target: ${target.label} at ${rawDistance}m, angle=${rawAngle}° (raw)")
        
        // Step 3: Apply smoothing
        val smoothedAngle = angleSmoother.smooth(rawAngle)
        val smoothedDistance = distanceSmoother.smooth(rawDistance)
        
        Log.d(TAG, "Smoothed: angle=${smoothedAngle}°, distance=${smoothedDistance}m")
        
        // Step 4: Make decision
        val rawDecision = decisionEngine.decide(smoothedAngle, smoothedDistance)
        
        // Step 5: Apply hysteresis
        val decision = decisionEngine.applyHysteresis(rawDecision, smoothedAngle)
        
        // Step 6: Check stability
        val isStable = stabilityTracker.update(decision)
        
        if (!isStable) {
            Log.d(TAG, "Decision not stable yet (${stabilityTracker.getFrameCount()}/${NavigationConfig.STABILITY_FRAMES} frames)")
            return
        }
        
        Log.d(TAG, "Decision stable: $decision (${stabilityTracker.getFrameCount()} frames)")
        
        val now = System.currentTimeMillis()
        val timeSinceLastSpeech = now - lastSpeechTimestamp
        
        // Step 7: Determine if we should speak with delta gating
        val decisionChanged = decision != currentDecision
        val isUrgent = decision.isUrgent()
        val repeatInterval = if (isUrgent) NavigationConfig.URGENT_REPEAT_MS else NavigationConfig.COOLDOWN_MS
        val timeOk = timeSinceLastSpeech >= repeatInterval
        
        // Calculate deltas
        val distanceDelta = if (lastSpokenDistance != null) {
            kotlin.math.abs(smoothedDistance - lastSpokenDistance!!)
        } else {
            Float.MAX_VALUE  // First time, always speak
        }
        
        val angleDelta = if (lastSpokenAngle != null) {
            kotlin.math.abs(smoothedAngle - lastSpokenAngle!!)
        } else {
            Float.MAX_VALUE  // First time, always speak
        }
        
        val distanceDeltaOk = distanceDelta >= NavigationConfig.DISTANCE_DELTA_THRESHOLD
        val angleDeltaOk = angleDelta >= NavigationConfig.ANGLE_DELTA_THRESHOLD
        
        // Speak if: decision changed OR (time passed AND meaningful delta)
        val shouldSpeak = decisionChanged || (timeOk && (distanceDeltaOk || angleDeltaOk))
        
        if (!shouldSpeak) {
            Log.d(TAG, "NavSkip: changed=$decisionChanged timeOk=$timeOk distOk=$distanceDeltaOk angleOk=$angleDeltaOk " +
                    "distΔ=${String.format("%.2f", distanceDelta)}m angΔ=${String.format("%.1f", angleDelta)}° " +
                    "timeSince=${timeSinceLastSpeech}ms")
            return
        }
        
        if (decisionChanged) {
            Log.d(TAG, "Decision changed: $currentDecision -> $decision")
        } else if (isUrgent) {
            Log.d(TAG, "Urgent decision repeat (${timeSinceLastSpeech}ms since last)")
        } else {
            Log.d(TAG, "Normal decision repeat (${timeSinceLastSpeech}ms since last, distΔ=${String.format("%.2f", distanceDelta)}m, angΔ=${String.format("%.1f", angleDelta)}°)")
        }
        
        // Step 8: Build speech message with distance context
        val baseMessage = decision.toSpeechText()
        val distanceInfo = String.format("%.1f meters", smoothedDistance)
        val message = "$baseMessage, $distanceInfo"
        
        // Step 9: Request speech
        val priority = if (decision.isUrgent()) {
            SpeechCoordinator.Priority.URGENT
        } else {
            SpeechCoordinator.Priority.NAVIGATION
        }
        
        val spoken = speechCoordinator.requestSpeech(
            message = message,
            priority = priority,
            interruptActive = decision.isUrgent()
        )
        
        if (spoken) {
            // Step 10: Suppress ClosestObjectSpeaker
            speechCoordinator.suppressClosestObjectSpeaker(NavigationConfig.SUPPRESSION_DURATION_MS)
            
            // Update state including deltas
            currentDecision = decision
            lastSpeechTimestamp = now
            lastSpokenDistance = smoothedDistance
            lastSpokenAngle = smoothedAngle
            
            Log.d(TAG, "✓ SPEAKING: \"$message\" [urgent=${decision.isUrgent()}]")
        }
    }
    
    /**
     * Reset navigation state
     */
    fun reset() {
        decisionEngine.reset()
        angleSmoother.reset()
        distanceSmoother.reset()
        stabilityTracker.reset()
        currentDecision = null
        lastSpeechTimestamp = 0L
        lastSpokenDistance = null
        lastSpokenAngle = null
        Log.d(TAG, "Navigation guidance reset")
    }
}
