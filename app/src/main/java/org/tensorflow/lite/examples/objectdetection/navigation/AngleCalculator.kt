package org.tensorflow.lite.examples.objectdetection.navigation

/**
 * Utility for calculating horizontal angles from object positions
 */
object AngleCalculator {
    
    /**
     * Calculate horizontal angle in degrees from normalized center X position
     * 
     * @param normalizedCenterX Horizontal center position (0.0 = left edge, 1.0 = right edge)
     * @param horizontalFovDegrees Camera horizontal field of view in degrees (default 60°)
     * @return Angle in degrees (-FOV/2 to +FOV/2, negative = left, positive = right)
     */
    fun calculateAngle(
        normalizedCenterX: Float,
        horizontalFovDegrees: Float = 60f
    ): Float {
        // Center of frame is at 0.5, so offset by 0.5 to get relative position
        // Multiply by FOV to get angle in degrees
        return (normalizedCenterX - 0.5f) * horizontalFovDegrees
    }
    
    /**
     * Get normalized center X from NavigationDetection
     * 
     * @param detection The detection object
     * @return Normalized center X position (0.0 to 1.0)
     */
    fun getNormalizedCenterX(detection: NavigationDetection): Float {
        return detection.xCenter
    }
}
