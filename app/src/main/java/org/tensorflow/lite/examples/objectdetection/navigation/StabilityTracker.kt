package org.tensorflow.lite.examples.objectdetection.navigation

/**
 * Tracks decision stability across frames to prevent jittery speech output
 * 
 * @param requiredFrames Number of consecutive frames with same decision before considering stable
 */
class StabilityTracker(private val requiredFrames: Int = 2) {
    
    private var currentDecision: NavigationDecision? = null
    private var frameCount: Int = 0
    
    /**
     * Update with a new decision
     * 
     * @param decision The current navigation decision
     * @return True if the decision is now stable (has been consistent for required frames)
     */
    fun update(decision: NavigationDecision): Boolean {
        return if (decision == currentDecision) {
            // Same decision, increment counter
            frameCount++
            isStable()
        } else {
            // Decision changed, reset counter
            currentDecision = decision
            frameCount = 1
            isStable()
        }
    }
    
    /**
     * Check if current decision is stable
     * 
     * @return True if decision has been consistent for at least requiredFrames
     */
    fun isStable(): Boolean {
        return frameCount >= requiredFrames
    }
    
    /**
     * Get current frame count
     */
    fun getFrameCount(): Int = frameCount
    
    /**
     * Get current decision
     */
    fun getCurrentDecision(): NavigationDecision? = currentDecision
    
    /**
     * Reset the tracker
     */
    fun reset() {
        currentDecision = null
        frameCount = 0
    }
}
