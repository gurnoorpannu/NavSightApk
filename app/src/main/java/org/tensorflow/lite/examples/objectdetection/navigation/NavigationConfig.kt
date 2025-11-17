package org.tensorflow.lite.examples.objectdetection.navigation

/**
 * Configuration parameters for navigation guidance system
 * 
 * Updated for partition-based navigation (3-region screen approach)
 */
object NavigationConfig {
    
    // Camera and detection parameters
    /** Horizontal field of view in degrees (typical smartphone camera) */
    const val HORIZONTAL_FOV_DEGREES = 60f
    
    /** Maximum distance to consider obstacles for navigation (meters) */
    const val NAVIGATION_DISTANCE_THRESHOLD = 3.5f
    
    /** Minimum confidence threshold for detections */
    const val MIN_CONFIDENCE = 0.40f
    
    // Partition-based thresholds
    /** Occupancy threshold for full block (triggers STOP if close) */
    const val FULL_BLOCK_THRESHOLD = 0.60f
    
    /** Occupancy threshold for large object (triggers lateral instruction) */
    const val LARGE_OBJECT_THRESHOLD = 0.40f
    
    /** Distance threshold for STOP decision (meters) */
    const val STOP_DISTANCE = 1.0f
    
    /** Distance threshold for alert/lateral instruction (meters) */
    const val ALERT_DISTANCE = 2.5f
    
    /** Occupancy threshold per partition (alternative metric) */
    const val OCCUPANCY_THRESHOLD_PER_PARTITION = 0.12f
    
    // Timing parameters
    /** Repeat interval for urgent messages (STOP, HUGE_AHEAD) (milliseconds) */
    const val NAV_URGENT_REPEAT_MS = 1200L
    
    /** Repeat interval for non-urgent messages (GO_STRAIGHT, side warnings) (milliseconds) */
    const val NAV_NONURGENT_REPEAT_MS = 5000L
    
    /** Duration to suppress ClosestObjectSpeaker after navigation speech (milliseconds) */
    const val SUPPRESSION_DURATION_MS = 1500L
    
    // Delta thresholds for re-announcement gating
    /** Minimum distance change to trigger re-announcement (meters) */
    const val DISTANCE_DELTA_THRESHOLD = 0.5f
    
    /** Minimum occupancy change to trigger re-announcement (fraction) */
    const val OCCUPANCY_DELTA_THRESHOLD = 0.10f
    
    // ========== DEPRECATED ANGLE-BASED CONSTANTS ==========
    // Kept for backwards compatibility only - not used by PartitionNavGuidance
    
    @Deprecated("Use partition-based thresholds instead")
    const val STOP_DISTANCE_THRESHOLD = 0.8f
    
    @Deprecated("Use partition-based thresholds instead")
    const val STOP_ANGLE_THRESHOLD = 20f
    
    @Deprecated("Use partition-based thresholds instead")
    const val ANGLE_TURN_LEFT = -35f
    
    @Deprecated("Use partition-based thresholds instead")
    const val ANGLE_SLIGHTLY_LEFT = -15f
    
    @Deprecated("Use partition-based thresholds instead")
    const val ANGLE_STRAIGHT_MIN = -12f
    
    @Deprecated("Use partition-based thresholds instead")
    const val ANGLE_STRAIGHT_MAX = 12f
    
    @Deprecated("Use partition-based thresholds instead")
    const val ANGLE_SLIGHTLY_RIGHT = 15f
    
    @Deprecated("Use partition-based thresholds instead")
    const val ANGLE_TURN_RIGHT = 35f
    
    @Deprecated("Use partition-based thresholds instead")
    const val ANGLE_HYSTERESIS = 10f
    
    @Deprecated("Use partition-based thresholds instead")
    const val SMOOTHING_ALPHA = 0.2f
    
    @Deprecated("Use partition-based thresholds instead")
    const val STABILITY_FRAMES = 3
    
    @Deprecated("Use NAV_NONURGENT_REPEAT_MS instead")
    const val COOLDOWN_MS = 5000L
    
    @Deprecated("Use NAV_URGENT_REPEAT_MS instead")
    const val URGENT_REPEAT_MS = 1200L
    
    @Deprecated("Use OCCUPANCY_DELTA_THRESHOLD instead")
    const val ANGLE_DELTA_THRESHOLD = 6.0f
}
