package org.tensorflow.lite.examples.objectdetection.navigation

import org.tensorflow.lite.task.vision.detector.Detection

/**
 * Utility to convert TensorFlow Lite Detection objects to NavigationDetection format.
 * Handles coordinate normalization and data extraction.
 */
object DetectionConverter {

    /**
     * Convert a TensorFlow Lite Detection to NavigationDetection with normalized coordinates.
     * 
     * @param detection TensorFlow Lite detection object
     * @param imageWidth Width of the source image
     * @param imageHeight Height of the source image
     * @return NavigationDetection with normalized coordinates [0.0, 1.0]
     */
    fun toNavigationDetection(
        detection: Detection,
        imageWidth: Int,
        imageHeight: Int
    ): NavigationDetection {
        val boundingBox = detection.boundingBox
        
        // Calculate center coordinates
        val xCenter = (boundingBox.left + boundingBox.right) / 2f
        val yCenter = (boundingBox.top + boundingBox.bottom) / 2f
        
        // Calculate dimensions
        val width = boundingBox.width()
        val height = boundingBox.height()
        
        // Normalize to [0.0, 1.0] range
        val normalizedXCenter = xCenter / imageWidth
        val normalizedYCenter = yCenter / imageHeight
        val normalizedWidth = width / imageWidth
        val normalizedHeight = height / imageHeight
        
        // Extract label and confidence
        val label = detection.categories.firstOrNull()?.label ?: "unknown"
        val confidence = detection.categories.firstOrNull()?.score ?: 0f
        
        return NavigationDetection(
            label = label,
            confidence = confidence,
            xCenter = normalizedXCenter,
            yCenter = normalizedYCenter,
            width = normalizedWidth,
            height = normalizedHeight
        )
    }

    /**
     * Convert a list of TensorFlow Lite Detections to NavigationDetections.
     */
    fun toNavigationDetections(
        detections: List<Detection>,
        imageWidth: Int,
        imageHeight: Int
    ): List<NavigationDetection> {
        return detections.map { toNavigationDetection(it, imageWidth, imageHeight) }
    }
}
