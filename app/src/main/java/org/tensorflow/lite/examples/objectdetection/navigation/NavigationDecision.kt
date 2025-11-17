package org.tensorflow.lite.examples.objectdetection.navigation

/**
 * Navigation decisions based on partition occupancy and overlap
 * 
 * Replaces angle-based decisions with region-based decisions:
 * - STOP: Urgent - huge obstacle very close
 * - STEP_LEFT/STEP_RIGHT: Center blocked, move laterally
 * - GO_STRAIGHT: Path forward is clear
 * 
 * Legacy angle-based decisions kept for backwards compatibility but deprecated
 */
enum class NavigationDecision {
    // Partition-based decisions (new)
    STOP,
    STEP_LEFT,
    STEP_RIGHT,
    GO_STRAIGHT,
    
    // Angle-based decisions (deprecated - kept for backwards compatibility)
    @Deprecated("Use partition-based decisions instead")
    TURN_LEFT,
    @Deprecated("Use partition-based decisions instead")
    SLIGHTLY_LEFT,
    @Deprecated("Use partition-based decisions instead")
    SLIGHTLY_RIGHT,
    @Deprecated("Use partition-based decisions instead")
    TURN_RIGHT;
    
    /**
     * Convert decision to speech text
     */
    fun toSpeechText(): String {
        return when (this) {
            STOP -> "Stop — huge obstacle ahead"
            STEP_LEFT -> "Huge object ahead — step left"
            STEP_RIGHT -> "Huge object ahead — step right"
            GO_STRAIGHT -> "Go straight"
            
            // Legacy angle-based
            TURN_LEFT -> "Turn left"
            SLIGHTLY_LEFT -> "Slightly left"
            SLIGHTLY_RIGHT -> "Slightly right"
            TURN_RIGHT -> "Turn right"
        }
    }
    
    /**
     * Check if this decision is urgent (requires immediate speech)
     */
    fun isUrgent(): Boolean {
        return this == STOP
    }
}
