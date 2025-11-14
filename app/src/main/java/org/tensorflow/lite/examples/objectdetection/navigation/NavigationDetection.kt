package org.tensorflow.lite.examples.objectdetection.navigation

/**
 * Normalized detection data for navigation processing.
 * All coordinates are normalized to [0.0, 1.0] range.
 */
data class NavigationDetection(
    val label: String,
    val confidence: Float,
    val xCenter: Float,      // Normalized horizontal center (0.0 = left, 1.0 = right)
    val yCenter: Float,      // Normalized vertical center (0.0 = top, 1.0 = bottom)
    val width: Float,        // Normalized width (0.0 to 1.0)
    val height: Float        // Normalized height (0.0 to 1.0)
)
