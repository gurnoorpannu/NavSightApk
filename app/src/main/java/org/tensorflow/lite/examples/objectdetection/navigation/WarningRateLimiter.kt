package org.tensorflow.lite.examples.objectdetection.navigation

import android.util.Log

/**
 * Warning Rate Limiter - Anti-Spam System
 * 
 * Prevents audio alert bombardment by implementing:
 * - Global cooldown (2.5s between ANY alerts)
 * - Per-object cooldown (5s per label)
 * - Directional cooldown (3s per label+direction)
 * - Movement sensitivity (only alert on distance changes)
 * - Hard suppression rules (FAR objects, edge cases)
 * 
 * This is ML-side logic only - does NOT handle TTS/UI.
 */
object WarningRateLimiter {

    // Cooldown durations (milliseconds)
    private const val GLOBAL_COOLDOWN_MS = 2500L      // 2.5 seconds between ANY alerts
    private const val PER_OBJECT_COOLDOWN_MS = 5000L  // 5 seconds per label
    private const val DIRECTIONAL_COOLDOWN_MS = 3000L // 3 seconds per label+direction
    
    // Hard suppression thresholds
    private const val MIN_WIDTH_THRESHOLD = 0.08f     // Ignore very small objects
    private const val EDGE_THRESHOLD = 0.05f          // Ignore objects at frame edges
    private const val EDGE_MAX_THRESHOLD = 0.95f
    
    // State tracking
    private var lastGlobalAlertTime: Long = 0L
    private val lastObjectAlertTime = mutableMapOf<String, Long>()
    private val lastDirectionalAlertTime = mutableMapOf<String, Long>()
    private val lastDistanceCategory = mutableMapOf<String, DistanceCategory>()

    /**
     * Determines if a guidance should be announced based on cooldown and suppression rules.
     * 
     * @param guidance The guidance to evaluate
     * @param currentWidth Optional width for edge detection (normalized 0.0-1.0)
     * @param currentXCenter Optional xCenter for edge detection (normalized 0.0-1.0)
     * @return true if announcement is allowed, false if suppressed
     */
    @Synchronized
    fun shouldAnnounce(
        guidance: Guidance,
        currentWidth: Float = 0.1f,
        currentXCenter: Float = 0.5f
    ): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Rule 1: Global cooldown check
        if (currentTime - lastGlobalAlertTime < GLOBAL_COOLDOWN_MS) {
            val remaining = GLOBAL_COOLDOWN_MS - (currentTime - lastGlobalAlertTime)
            Log.d("WarningRateLimiter", "SUPPRESSED: Global cooldown active (${remaining}ms remaining)")
            return false
        }
        
        // Rule 2: Hard suppression - FAR distance
        if (guidance.distance == DistanceCategory.FAR) {
            Log.d("WarningRateLimiter", "SUPPRESSED: FAR distance (${guidance.label})")
            return false
        }
        
        // Rule 3: Hard suppression - MEDIUM objects (unless center + high priority)
        if (guidance.distance == DistanceCategory.MEDIUM) {
            val isCenterAndImportant = guidance.direction == Direction.CENTER && guidance.priority > 10.0f
            if (!isCenterAndImportant) {
                return false
            }
        }
        
        // Rule 4: Hard suppression - objects too small
        if (currentWidth < MIN_WIDTH_THRESHOLD) {
            return false
        }
        
        // Rule 5: Hard suppression - objects at frame edges
        if (currentXCenter < EDGE_THRESHOLD || currentXCenter > EDGE_MAX_THRESHOLD) {
            return false
        }
        
        // Rule 6: Per-object cooldown check
        val lastObjectTime = lastObjectAlertTime[guidance.label] ?: 0L
        if (currentTime - lastObjectTime < PER_OBJECT_COOLDOWN_MS) {
            val remaining = PER_OBJECT_COOLDOWN_MS - (currentTime - lastObjectTime)
            Log.d("WarningRateLimiter", "SUPPRESSED: Per-object cooldown for '${guidance.label}' (${remaining}ms remaining)")
            return false
        }
        
        // Rule 7: Directional cooldown check
        val directionalKey = "${guidance.label}-${guidance.direction}"
        val lastDirectionalTime = lastDirectionalAlertTime[directionalKey] ?: 0L
        if (currentTime - lastDirectionalTime < DIRECTIONAL_COOLDOWN_MS) {
            return false
        }
        
        // Rule 8: Movement sensitivity - only alert on distance category changes
        val lastDistance = lastDistanceCategory[guidance.label]
        if (lastDistance != null) {
            // Only trigger if distance became MORE dangerous
            val becameMoreDangerous = when {
                lastDistance == DistanceCategory.FAR && guidance.distance == DistanceCategory.MEDIUM -> true
                lastDistance == DistanceCategory.FAR && guidance.distance == DistanceCategory.CLOSE -> true
                lastDistance == DistanceCategory.FAR && guidance.distance == DistanceCategory.VERY_CLOSE -> true
                lastDistance == DistanceCategory.MEDIUM && guidance.distance == DistanceCategory.CLOSE -> true
                lastDistance == DistanceCategory.MEDIUM && guidance.distance == DistanceCategory.VERY_CLOSE -> true
                lastDistance == DistanceCategory.CLOSE && guidance.distance == DistanceCategory.VERY_CLOSE -> true
                else -> false
            }
            
            if (!becameMoreDangerous) {
                return false
            }
        }
        
        // All checks passed - announcement allowed
        Log.d("WarningRateLimiter", "ALLOWED: ${guidance.label} ${guidance.distance} ${guidance.direction}")
        return true
    }

    /**
     * Records that an announcement was made for the given guidance.
     * Updates all cooldown timers and distance tracking.
     * 
     * @param guidance The guidance that was announced
     */
    @Synchronized
    fun recordAnnouncement(guidance: Guidance) {
        val currentTime = System.currentTimeMillis()
        
        // Update global cooldown
        lastGlobalAlertTime = currentTime
        
        // Update per-object cooldown
        lastObjectAlertTime[guidance.label] = currentTime
        
        // Update directional cooldown
        val directionalKey = "${guidance.label}-${guidance.direction}"
        lastDirectionalAlertTime[directionalKey] = currentTime
        
        // Update distance tracking for movement sensitivity
        lastDistanceCategory[guidance.label] = guidance.distance
    }

    /**
     * Resets all cooldown timers and tracking state.
     * Useful for testing or when starting a new navigation session.
     */
    @Synchronized
    fun reset() {
        lastGlobalAlertTime = 0L
        lastObjectAlertTime.clear()
        lastDirectionalAlertTime.clear()
        lastDistanceCategory.clear()
    }

    /**
     * Gets the remaining cooldown time for a specific object label.
     * 
     * @param label The object label to check
     * @return Remaining cooldown in milliseconds, or 0 if no cooldown active
     */
    @Synchronized
    fun getRemainingCooldown(label: String): Long {
        val lastTime = lastObjectAlertTime[label] ?: return 0L
        val elapsed = System.currentTimeMillis() - lastTime
        val remaining = PER_OBJECT_COOLDOWN_MS - elapsed
        return if (remaining > 0) remaining else 0L
    }

    /**
     * Gets debug information about current cooldown state.
     * Useful for testing and debugging.
     */
    @Synchronized
    fun getDebugInfo(): String {
        val currentTime = System.currentTimeMillis()
        val globalRemaining = GLOBAL_COOLDOWN_MS - (currentTime - lastGlobalAlertTime)
        
        return buildString {
            appendLine("WarningRateLimiter Debug Info:")
            appendLine("  Global cooldown: ${if (globalRemaining > 0) "${globalRemaining}ms" else "ready"}")
            appendLine("  Tracked objects: ${lastObjectAlertTime.size}")
            lastObjectAlertTime.forEach { (label, time) ->
                val remaining = PER_OBJECT_COOLDOWN_MS - (currentTime - time)
                appendLine("    $label: ${if (remaining > 0) "${remaining}ms" else "ready"}")
            }
            appendLine("  Last distances: $lastDistanceCategory")
        }
    }
}
