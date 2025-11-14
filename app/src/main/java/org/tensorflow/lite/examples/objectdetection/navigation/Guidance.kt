package org.tensorflow.lite.examples.objectdetection.navigation

/**
 * Final navigation guidance output for a single prioritized object.
 * This is what gets converted to TTS/haptic feedback.
 */
data class Guidance(
    val label: String,
    val direction: Direction,
    val distance: DistanceCategory,
    val priority: Float
)
