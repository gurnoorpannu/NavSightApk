package org.tensorflow.lite.examples.objectdetection.navigation

import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.examples.objectdetection.DepthEstimator

/**
 * Utility for enriching NavigationDetection objects with depth information.
 * 
 * Takes detections from object detection and adds accurate depth measurements
 * from the MiDaS depth estimation model.
 */
object DepthEnricher {
    
    private const val TAG = "DepthEnricher"
    
    /**
     * Enrich a single NavigationDetection with depth information
     * 
     * @param detection The detection to enrich
     * @param depthEstimator The depth estimator instance
     * @param imageWidth Original image width in pixels
     * @param imageHeight Original image height in pixels
     * @return NavigationDetection with depth information added
     */
    fun enrichWithDepth(
        detection: NavigationDetection,
        depthEstimator: DepthEstimator,
        imageWidth: Int,
        imageHeight: Int
    ): NavigationDetection {
        try {
            // Convert normalized detection coordinates to bounding box
            val boundingBox = toBoundingBox(detection, imageWidth, imageHeight)
            
            // Get median depth for this region
            val depthValue = depthEstimator.getMedianDepthForRegion(
                boundingBox, imageWidth, imageHeight
            )
            
            // Convert to meters if depth is available
            val distanceMeters = depthValue?.let { depthEstimator.depthToMeters(it) }
            
            // Log depth information for debugging
            if (depthValue != null && distanceMeters != null) {
                Log.d(TAG, "Enriched ${detection.label}: depth=$depthValue, distance=${distanceMeters}m")
            } else {
                Log.d(TAG, "No depth available for ${detection.label}")
            }
            
            // Return detection with depth info
            return detection.copy(
                depthValue = depthValue,
                distanceMeters = distanceMeters
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error enriching detection with depth: ${e.message}", e)
            // Return original detection without depth on error
            return detection
        }
    }
    
    /**
     * Enrich a list of NavigationDetections with depth information
     * 
     * @param detections List of detections to enrich
     * @param depthEstimator The depth estimator instance
     * @param imageWidth Original image width in pixels
     * @param imageHeight Original image height in pixels
     * @return List of NavigationDetections with depth information added
     */
    fun enrichWithDepth(
        detections: List<NavigationDetection>,
        depthEstimator: DepthEstimator,
        imageWidth: Int,
        imageHeight: Int
    ): List<NavigationDetection> {
        return detections.map { detection ->
            enrichWithDepth(detection, depthEstimator, imageWidth, imageHeight)
        }
    }
    
    /**
     * Convert normalized NavigationDetection coordinates to a bounding box in normalized space
     * 
     * @param detection The detection with normalized coordinates
     * @param imageWidth Original image width (used for aspect ratio)
     * @param imageHeight Original image height (used for aspect ratio)
     * @return RectF with normalized coordinates [0.0, 1.0]
     */
    private fun toBoundingBox(
        detection: NavigationDetection,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        // Calculate bounding box from center and dimensions
        val left = detection.xCenter - (detection.width / 2f)
        val right = detection.xCenter + (detection.width / 2f)
        val top = detection.yCenter - (detection.height / 2f)
        val bottom = detection.yCenter + (detection.height / 2f)
        
        // Clamp to [0.0, 1.0] range
        return RectF(
            left.coerceIn(0f, 1f),
            top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f),
            bottom.coerceIn(0f, 1f)
        )
    }
    
    /**
     * Check if depth estimation is available for a detection
     * 
     * @param detection The detection to check
     * @return True if depth information is available
     */
    fun hasDepthInfo(detection: NavigationDetection): Boolean {
        return detection.depthValue != null && detection.distanceMeters != null
    }
    
    /**
     * Get a summary of depth enrichment for a list of detections
     * Useful for debugging and monitoring
     * 
     * @param detections List of detections
     * @return Summary string
     */
    fun getEnrichmentSummary(detections: List<NavigationDetection>): String {
        val total = detections.size
        val withDepth = detections.count { hasDepthInfo(it) }
        val percentage = if (total > 0) (withDepth * 100) / total else 0
        
        return "Depth enrichment: $withDepth/$total ($percentage%) detections have depth info"
    }
}
