package org.tensorflow.lite.examples.objectdetection.navigation

/**
 * Exponential Moving Average (EMA) smoother to reduce jitter in measurements
 * 
 * @param alpha Smoothing factor (0.0 to 1.0). Higher = more responsive, lower = more smooth
 */
class ExponentialSmoother(private val alpha: Float = 0.3f) {
    
    private var smoothedValue: Float? = null
    
    /**
     * Apply smoothing to a new value
     * 
     * @param newValue The new measurement to smooth
     * @return Smoothed value
     */
    fun smooth(newValue: Float): Float {
        val current = smoothedValue
        
        return if (current == null) {
            // First value, no smoothing needed
            smoothedValue = newValue
            newValue
        } else {
            // Apply EMA: smoothed = alpha * new + (1 - alpha) * previous
            val result = alpha * newValue + (1 - alpha) * current
            smoothedValue = result
            result
        }
    }
    
    /**
     * Reset the smoother (clears history)
     */
    fun reset() {
        smoothedValue = null
    }
    
    /**
     * Get current smoothed value (null if no values processed yet)
     */
    fun getCurrentValue(): Float? = smoothedValue
}
