package org.tensorflow.lite.examples.objectdetection

import android.util.Log

/**
 * Configuration object for MiDaS depth estimation
 * 
 * Provides tunable parameters for depth estimation, distance calibration,
 * and performance settings. Can be adjusted based on real-world testing.
 */
object DepthConfig {
    
    private const val TAG = "DepthConfig"
    
    // ===== MODEL SETTINGS =====
    
    /** Path to MiDaS model in assets */
    const val MODEL_PATH = "midas.tflite"
    
    /** Input size for MiDaS model (256x256) */
    const val INPUT_SIZE = 256
    
    /** Number of color channels (RGB) */
    const val CHANNELS = 3
    
    // ===== INFERENCE SETTINGS =====
    
    /** Enable GPU acceleration if available */
    var USE_GPU = true
    
    /** Enable NNAPI acceleration if GPU unavailable */
    var USE_NNAPI = true
    
    /** Number of threads for CPU inference */
    var NUM_THREADS = 2
    
    // ===== CALIBRATION SETTINGS =====
    
    /**
     * Depth scale factor for converting relative depth to meters
     * 
     * Formula: distance_meters = relative_depth / DEPTH_SCALE_FACTOR
     * 
     * MiDaS outputs relative depth values typically ranging:
     * - Close objects: ~100-300
     * - Medium objects: ~300-600
     * - Far objects: ~600-1000+
     * 
     * Adjust this value based on real-world testing:
     * - Increase if distances seem too long
     * - Decrease if distances seem too short
     * 
     * Default: 150.0 (empirically determined for typical indoor scenes)
     */
    var DEPTH_SCALE_FACTOR = 150.0f
        set(value) {
            field = value
            Log.i(TAG, "Depth scale factor updated to: $value")
        }
    
    /** Minimum depth in meters (prevents extreme close values) */
    const val MIN_DEPTH_METERS = 0.1f
    
    /** Maximum depth in meters (prevents extreme far values) */
    const val MAX_DEPTH_METERS = 10.0f
    
    // ===== DISTANCE THRESHOLDS (METERS) =====
    
    /**
     * Distance threshold for "VERY_CLOSE" category
     * Objects closer than this are considered immediate obstacles
     */
    var DEPTH_VERY_CLOSE_THRESHOLD = 1.0f
        set(value) {
            field = value
            Log.i(TAG, "Very close threshold updated to: ${value}m")
        }
    
    /**
     * Distance threshold for "CLOSE" category
     * Objects closer than this require attention
     */
    var DEPTH_CLOSE_THRESHOLD = 2.0f
        set(value) {
            field = value
            Log.i(TAG, "Close threshold updated to: ${value}m")
        }
    
    /**
     * Distance threshold for "MEDIUM" category
     * Objects closer than this are approaching
     */
    var DEPTH_MEDIUM_THRESHOLD = 4.0f
        set(value) {
            field = value
            Log.i(TAG, "Medium threshold updated to: ${value}m")
        }
    
    // ===== PERFORMANCE SETTINGS =====
    
    /** Maximum acceptable inference time in milliseconds */
    const val MAX_INFERENCE_TIME_MS = 150L
    
    /** Whether to cache depth maps for quick access */
    const val CACHE_DEPTH_MAP = true
    
    /** Whether to log depth values for calibration */
    var ENABLE_CALIBRATION_LOGGING = false
        set(value) {
            field = value
            Log.i(TAG, "Calibration logging ${if (value) "enabled" else "disabled"}")
        }
    
    // ===== CALIBRATION HELPERS =====
    
    /**
     * Log depth calibration data for a detection
     * Useful for collecting real-world measurements
     * 
     * @param label Object label
     * @param relativeDepth Raw depth value from MiDaS
     * @param distanceMeters Converted distance in meters
     * @param actualDistance Known actual distance (if available)
     */
    fun logCalibrationData(
        label: String,
        relativeDepth: Float,
        distanceMeters: Float,
        actualDistance: Float? = null
    ) {
        if (!ENABLE_CALIBRATION_LOGGING) return
        
        val actualStr = actualDistance?.let { " (actual: ${it}m)" } ?: ""
        Log.i(TAG, "CALIBRATION: $label - relative=$relativeDepth, computed=${distanceMeters}m$actualStr")
    }
    
    /**
     * Calculate recommended scale factor based on known measurements
     * 
     * @param relativeDepth Raw depth value from MiDaS
     * @param actualDistance Known actual distance in meters
     * @return Recommended scale factor
     */
    fun calculateScaleFactor(relativeDepth: Float, actualDistance: Float): Float {
        val recommendedFactor = actualDistance * relativeDepth
        Log.i(TAG, "Recommended scale factor: $recommendedFactor (for depth=$relativeDepth at ${actualDistance}m)")
        return recommendedFactor
    }
    
    /**
     * Reset all settings to defaults
     */
    fun resetToDefaults() {
        USE_GPU = true
        USE_NNAPI = true
        NUM_THREADS = 2
        DEPTH_SCALE_FACTOR = 2.0f
        DEPTH_VERY_CLOSE_THRESHOLD = 1.0f
        DEPTH_CLOSE_THRESHOLD = 2.0f
        DEPTH_MEDIUM_THRESHOLD = 4.0f
        ENABLE_CALIBRATION_LOGGING = false
        Log.i(TAG, "All settings reset to defaults")
    }
    
    /**
     * Get current configuration as a string
     */
    fun getConfigSummary(): String {
        return """
            DepthConfig Summary:
            - Model: $MODEL_PATH
            - GPU: $USE_GPU, NNAPI: $USE_NNAPI, Threads: $NUM_THREADS
            - Scale Factor: $DEPTH_SCALE_FACTOR
            - Thresholds: Very Close=${DEPTH_VERY_CLOSE_THRESHOLD}m, Close=${DEPTH_CLOSE_THRESHOLD}m, Medium=${DEPTH_MEDIUM_THRESHOLD}m
            - Calibration Logging: $ENABLE_CALIBRATION_LOGGING
        """.trimIndent()
    }
}
