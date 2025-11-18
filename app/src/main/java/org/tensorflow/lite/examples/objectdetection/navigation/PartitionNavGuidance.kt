package org.tensorflow.lite.examples.objectdetection.navigation

import android.content.Context
import android.util.Log

/**
 * Partition-Based Navigation Guidance
 * 
 * Replaces angle-based navigation with a simple 3-partition screen approach:
 * - Divide frame into LEFT / CENTER / RIGHT regions
 * - Make decisions based on region occupancy and overlap
 * - More deterministic and reliable than angle-based smoothing
 */
class PartitionNavGuidance(
    context: Context,
    private val speechCoordinator: SpeechCoordinator
) {
    
    companion object {
        private const val TAG = "PartitionNavGuidance"
    }
    
    // State tracking
    private var lastSpeechTimestamp: Long = 0L
    private var currentDecision: NavigationDecision? = null
    private var lastSpokenDistance: Float? = null
    private var lastSpokenOccupancy: Float? = null
    private var lastSpokenObjectLabel: String? = null  // Track which object we last spoke about
    
    // "Path clear" announcement tracking
    private var lastPathClearTimestamp: Long = 0L
    private val PATH_CLEAR_REPEAT_INTERVAL_MS = 8000L  // Repeat every 8 seconds if still clear
    
    /**
     * Update navigation guidance with new detections
     * 
     * @param detections List of depth-enriched NavigationDetections
     * @param imageWidth Image width for partition calculation
     * @param imageHeight Image height (unused but kept for consistency)
     */
    fun update(
        detections: List<NavigationDetection>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        // Step 1: Filter valid detections
        val validTargets = detections.filter { detection ->
            detection.confidence >= NavigationConfig.MIN_CONFIDENCE &&
            detection.distanceMeters != null &&
            detection.distanceMeters <= NavigationConfig.NAVIGATION_DISTANCE_THRESHOLD
        }
        
        if (validTargets.isEmpty()) {
            handlePathClear()
            return
        }
        
        // Reset path clear timestamp since we have obstacles
        lastPathClearTimestamp = 0L
        
        // Step 2: Analyze partitions for each detection
        val partitionAnalyses = validTargets.map { detection ->
            analyzeDetectionPartitions(detection, imageWidth)
        }
        
        // Step 3: Make navigation decision
        val decisionResult = makeDecision(partitionAnalyses, imageWidth)
        
        if (decisionResult == null) {
            Log.d(TAG, "No actionable decision")
            return
        }
        
        val (decision, distance, maxOccupancy, zoneCoverage, objectLabel) = decisionResult
        
        // Step 4: Check if we should speak
        val now = System.currentTimeMillis()
        val timeSinceLastSpeech = now - lastSpeechTimestamp
        
        // Treat STEP_LEFT and STEP_RIGHT as the same category (lateral avoidance)
        // This prevents announcing every time the direction flips
        val decisionCategory = when (decision) {
            NavigationDecision.STEP_LEFT, NavigationDecision.STEP_RIGHT -> "LATERAL"
            NavigationDecision.STOP -> "STOP"
            NavigationDecision.GO_STRAIGHT -> "STRAIGHT"
            else -> decision.name
        }
        
        val currentCategory = when (currentDecision) {
            NavigationDecision.STEP_LEFT, NavigationDecision.STEP_RIGHT -> "LATERAL"
            NavigationDecision.STOP -> "STOP"
            NavigationDecision.GO_STRAIGHT -> "STRAIGHT"
            else -> currentDecision?.name
        }
        
        // Check if the object itself changed (different object detected)
        val objectChanged = objectLabel != lastSpokenObjectLabel
        
        val categoryChanged = decisionCategory != currentCategory
        val isUrgent = decision.isUrgent()
        val repeatInterval = if (isUrgent) {
            NavigationConfig.NAV_URGENT_REPEAT_MS
        } else {
            NavigationConfig.NAV_NONURGENT_REPEAT_MS
        }
        val timeOk = timeSinceLastSpeech >= repeatInterval
        
        // Calculate deltas
        val distanceDelta = if (lastSpokenDistance != null) {
            kotlin.math.abs(distance - lastSpokenDistance!!)
        } else {
            Float.MAX_VALUE
        }
        
        val occupancyDelta = if (lastSpokenOccupancy != null) {
            kotlin.math.abs(maxOccupancy - lastSpokenOccupancy!!)
        } else {
            Float.MAX_VALUE
        }
        
        val distanceDeltaOk = distanceDelta >= NavigationConfig.DISTANCE_DELTA_THRESHOLD
        val occupancyDeltaOk = occupancyDelta >= NavigationConfig.OCCUPANCY_DELTA_THRESHOLD
        
        // ANTI-SPAM: Enforce minimum time between ANY announcements
        // This prevents rapid-fire speech when detector flickers between objects
        val MIN_TIME_BETWEEN_ANY_SPEECH_MS = 2000L
        
        if (timeSinceLastSpeech < MIN_TIME_BETWEEN_ANY_SPEECH_MS) {
            Log.d(TAG, "NavSkip: ANTI-SPAM cooldown (${timeSinceLastSpeech}ms < ${MIN_TIME_BETWEEN_ANY_SPEECH_MS}ms) " +
                    "obj=$objectLabel zone=${zoneCoverage.dominantZone()} occ=${String.format("%.2f", maxOccupancy)}")
            return
        }
        
        // Speak if: object changed OR (time passed AND meaningful delta)
        // Category changes alone don't trigger speech unless object changed
        val shouldSpeak = objectChanged || (timeOk && (distanceDeltaOk || occupancyDeltaOk))
        
        if (!shouldSpeak) {
            Log.d(TAG, "NavSkip: objChanged=$objectChanged timeOk=$timeOk distOk=$distanceDeltaOk occOk=$occupancyDeltaOk " +
                    "obj=$objectLabel zone=${zoneCoverage.dominantZone()} occ=${String.format("%.2f", maxOccupancy)} " +
                    "cat=$decisionCategory timeSince=${timeSinceLastSpeech}ms")
            return
        }
        
        if (objectChanged) {
            Log.d(TAG, "Object changed: $lastSpokenObjectLabel -> $objectLabel (category: $currentCategory -> $decisionCategory)")
        } else if (isUrgent) {
            Log.d(TAG, "Urgent decision repeat (${timeSinceLastSpeech}ms since last)")
        } else {
            Log.d(TAG, "Normal decision repeat (${timeSinceLastSpeech}ms since last)")
        }
        
        // Step 5: Build speech message with object label (without distance)
        // Format: "frisbee ahead of you, move right"
        val message = when (decision) {
            NavigationDecision.STOP -> "$objectLabel ahead of you, stop"
            NavigationDecision.STEP_LEFT -> "$objectLabel ahead of you, move left"
            NavigationDecision.STEP_RIGHT -> "$objectLabel ahead of you, move right"
            NavigationDecision.GO_STRAIGHT -> "$objectLabel ahead of you, move straight"
            else -> "$objectLabel ahead of you"
        }
        
        // Step 6: Request speech
        val priority = if (decision.isUrgent()) {
            SpeechCoordinator.Priority.URGENT
        } else {
            SpeechCoordinator.Priority.NAVIGATION
        }
        
        val spoken = speechCoordinator.requestSpeech(
            message = message,
            priority = priority,
            interruptActive = decision.isUrgent()
        )
        
        if (spoken) {
            // Step 7: Suppress ClosestObjectSpeaker
            speechCoordinator.suppressClosestObjectSpeaker(NavigationConfig.SUPPRESSION_DURATION_MS)
            
            // Update state including object label
            currentDecision = decision
            lastSpeechTimestamp = now
            lastSpokenDistance = distance
            lastSpokenOccupancy = maxOccupancy
            lastSpokenObjectLabel = objectLabel
            
            Log.d(TAG, "✓ SPEAKING: \"$message\" [urgent=${decision.isUrgent()}] obj=$objectLabel zone=${zoneCoverage.dominantZone()} occ=${String.format("%.2f", maxOccupancy)}")
        }
    }
    
    /**
     * Analyze which partitions a detection occupies
     */
    private fun analyzeDetectionPartitions(
        detection: NavigationDetection,
        imageWidth: Int
    ): PartitionAnalysis {
        // NavigationDetection uses normalized coordinates (0.0-1.0)
        // Convert to pixel coordinates for partition analysis
        val centerX = detection.xCenter * imageWidth
        val halfWidth = (detection.width * imageWidth) / 2f
        val left = centerX - halfWidth
        val right = centerX + halfWidth
        
        // Define partition boundaries (in pixels)
        val leftBoundary = imageWidth / 3f
        val rightBoundary = imageWidth * 2f / 3f
        
        // Determine which partitions the bbox overlaps
        val overlapsLeft = left < leftBoundary
        val overlapsCenter = right > leftBoundary && left < rightBoundary
        val overlapsRight = right > rightBoundary
        
        // Calculate occupancy per partition
        val bboxWidth = right - left
        val maxOccupancyOverall = bboxWidth / imageWidth
        
        // Calculate which partition the center is in
        val centerPartition = when {
            centerX < leftBoundary -> Partition.LEFT
            centerX < rightBoundary -> Partition.CENTER
            else -> Partition.RIGHT
        }
        
        // Calculate occupancy percentages per partition
        val leftPct = if (overlapsLeft) {
            val overlapWidth = minOf(right, leftBoundary) - left
            overlapWidth / leftBoundary
        } else 0f
        
        val centerPct = if (overlapsCenter) {
            val overlapLeft = maxOf(left, leftBoundary)
            val overlapRight = minOf(right, rightBoundary)
            val overlapWidth = overlapRight - overlapLeft
            overlapWidth / (rightBoundary - leftBoundary)
        } else 0f
        
        val rightPct = if (overlapsRight) {
            val overlapWidth = right - maxOf(left, rightBoundary)
            overlapWidth / (imageWidth - rightBoundary)
        } else 0f
        
        return PartitionAnalysis(
            detection = detection,
            overlapsLeft = overlapsLeft,
            overlapsCenter = overlapsCenter,
            overlapsRight = overlapsRight,
            centerPartition = centerPartition,
            maxOccupancyOverall = maxOccupancyOverall,
            zoneCoverage = ZoneCoverage(leftPct, centerPct, rightPct)
        )
    }
    
    /**
     * Make navigation decision based on partition analyses
     * 
     * SIMPLIFIED APPROACH:
     * - Use object's CENTER POINT to determine which partition it's in
     * - If center is in LEFT partition → GO_STRAIGHT (obstacle on left)
     * - If center is in RIGHT partition → GO_STRAIGHT (obstacle on right)
     * - If center is in CENTER partition → STEP_LEFT or STEP_RIGHT (avoid it)
     * - Exception: Very large objects (STOP or choose clearer side)
     */
    private fun makeDecision(
        analyses: List<PartitionAnalysis>,
        imageWidth: Int
    ): DecisionResult? {
        if (analyses.isEmpty()) return null
        
        // Find closest detection
        val closest = analyses.minByOrNull { it.detection.distanceMeters ?: Float.MAX_VALUE }
            ?: return null
        
        val distance = closest.detection.distanceMeters ?: return null
        val maxOcc = closest.maxOccupancyOverall
        
        Log.d(TAG, "Closest: ${closest.detection.label} at ${distance}m, " +
                "occ=${String.format("%.2f", maxOcc)}, " +
                "centerPartition=${closest.centerPartition}")
        
        // Priority 1: STOP - full block and very close
        if (maxOcc >= NavigationConfig.FULL_BLOCK_THRESHOLD && 
            distance <= NavigationConfig.STOP_DISTANCE) {
            Log.d(TAG, "Decision: STOP (occ=${String.format("%.2f", maxOcc)} >= ${NavigationConfig.FULL_BLOCK_THRESHOLD}, dist=${distance}m)")
            return DecisionResult(NavigationDecision.STOP, distance, maxOcc, closest.zoneCoverage, closest.detection.label)
        }
        
        // Priority 2: Very large object - choose clearer side
        if (maxOcc >= NavigationConfig.LARGE_OBJECT_THRESHOLD && distance <= NavigationConfig.ALERT_DISTANCE) {
            val lateralDecision = chooseLateralDirection(analyses, imageWidth)
            Log.d(TAG, "Decision: LARGE_OBJECT -> $lateralDecision (occ=${String.format("%.2f", maxOcc)})")
            return DecisionResult(lateralDecision, distance, maxOcc, closest.zoneCoverage, closest.detection.label)
        }
        
        // Priority 3: Simple partition-based decision using CENTER POINT
        return when (closest.centerPartition) {
            Partition.LEFT -> {
                Log.d(TAG, "Decision: GO_STRAIGHT (object center in LEFT partition)")
                DecisionResult(NavigationDecision.GO_STRAIGHT, distance, maxOcc, closest.zoneCoverage, closest.detection.label)
            }
            Partition.RIGHT -> {
                Log.d(TAG, "Decision: GO_STRAIGHT (object center in RIGHT partition)")
                DecisionResult(NavigationDecision.GO_STRAIGHT, distance, maxOcc, closest.zoneCoverage, closest.detection.label)
            }
            Partition.CENTER -> {
                // Object is directly ahead - need to avoid it
                val lateralDecision = chooseLateralDirection(analyses, imageWidth)
                Log.d(TAG, "Decision: AVOID_CENTER -> $lateralDecision (object center in CENTER partition)")
                DecisionResult(lateralDecision, distance, maxOcc, closest.zoneCoverage, closest.detection.label)
            }
        }
    }
    
    /**
     * Choose which lateral direction has more free space
     * 
     * Strategy: Calculate occupancy on left vs right side of frame
     * If one side has less occupancy, suggest stepping toward that side
     */
    private fun chooseLateralDirection(
        analyses: List<PartitionAnalysis>,
        imageWidth: Int
    ): NavigationDecision {
        // Calculate total occupancy on left and right sides
        // Sum up occupancy percentages for objects overlapping each side
        var leftOccupancy = 0f
        var rightOccupancy = 0f
        
        for (analysis in analyses) {
            if (analysis.overlapsLeft) {
                leftOccupancy += analysis.zoneCoverage.leftPct
            }
            if (analysis.overlapsRight) {
                rightOccupancy += analysis.zoneCoverage.rightPct
            }
        }
        
        Log.d(TAG, "Lateral choice: leftOcc=${String.format("%.2f", leftOccupancy)}, rightOcc=${String.format("%.2f", rightOccupancy)}")
        
        // Choose side with less occupancy (more free space)
        return if (leftOccupancy < rightOccupancy) {
            NavigationDecision.STEP_LEFT
        } else if (rightOccupancy < leftOccupancy) {
            NavigationDecision.STEP_RIGHT
        } else {
            // Equal occupancy - prefer right as deterministic fallback
            NavigationDecision.STEP_RIGHT
        }
    }
    
    /**
     * Handle "path clear" announcement when no obstacles detected
     * 
     * Strategy:
     * - Announce immediately when no obstacles detected (after objects disappear)
     * - Respect 2-second anti-spam cooldown to avoid rapid announcements
     * - Repeat every 8 seconds if path remains clear
     */
    private fun handlePathClear() {
        val now = System.currentTimeMillis()
        val timeSinceLastPathClear = now - lastPathClearTimestamp
        val timeSinceLastSpeech = now - lastSpeechTimestamp
        
        // For the first announcement (lastPathClearTimestamp == 0), announce immediately
        // For subsequent announcements, enforce the repeat interval
        val isFirstAnnouncement = lastPathClearTimestamp == 0L
        
        // Respect anti-spam cooldown (2 seconds between ANY announcements)
        val MIN_TIME_BETWEEN_ANY_SPEECH_MS = 2000L
        if (timeSinceLastSpeech < MIN_TIME_BETWEEN_ANY_SPEECH_MS) {
            Log.d(TAG, "Path clear skipped: anti-spam cooldown (${timeSinceLastSpeech}ms < ${MIN_TIME_BETWEEN_ANY_SPEECH_MS}ms)")
            return
        }
        
        if (isFirstAnnouncement || timeSinceLastPathClear >= PATH_CLEAR_REPEAT_INTERVAL_MS) {
            // Announce path clear
            val message = "path clear, move straight"
            
            Log.d(TAG, "Attempting path clear announcement: first=$isFirstAnnouncement, timeSince=${timeSinceLastPathClear}ms")
            
            val spoken = speechCoordinator.requestSpeech(
                message = message,
                priority = SpeechCoordinator.Priority.NAVIGATION,
                interruptActive = false
            )
            
            if (spoken) {
                lastPathClearTimestamp = now
                lastSpeechTimestamp = now
                lastSpokenObjectLabel = null  // Clear object tracking
                
                // Suppress ClosestObjectSpeaker
                speechCoordinator.suppressClosestObjectSpeaker(NavigationConfig.SUPPRESSION_DURATION_MS)
                
                Log.d(TAG, "✓ SPEAKING: \"$message\" [path clear, first=$isFirstAnnouncement]")
            } else {
                Log.d(TAG, "✗ Path clear speech BLOCKED by coordinator [first=$isFirstAnnouncement]")
            }
        } else {
            Log.d(TAG, "Path clear skipped: waiting for interval (${timeSinceLastPathClear}ms < ${PATH_CLEAR_REPEAT_INTERVAL_MS}ms)")
        }
    }
    
    /**
     * Reset navigation state
     */
    fun reset() {
        currentDecision = null
        lastSpeechTimestamp = 0L
        lastSpokenDistance = null
        lastSpokenOccupancy = null
        lastSpokenObjectLabel = null
        lastPathClearTimestamp = 0L
        Log.d(TAG, "Partition navigation guidance reset")
    }
    
    // Data classes
    
    private data class PartitionAnalysis(
        val detection: NavigationDetection,
        val overlapsLeft: Boolean,
        val overlapsCenter: Boolean,
        val overlapsRight: Boolean,
        val centerPartition: Partition,
        val maxOccupancyOverall: Float,
        val zoneCoverage: ZoneCoverage
    )
    
    private data class DecisionResult(
        val decision: NavigationDecision,
        val distance: Float,
        val maxOccupancy: Float,
        val zoneCoverage: ZoneCoverage,
        val objectLabel: String  // Added to include object name in speech
    )
    
    private data class ZoneCoverage(
        val leftPct: Float,
        val centerPct: Float,
        val rightPct: Float
    ) {
        fun dominantZone(): String {
            val max = maxOf(leftPct, centerPct, rightPct)
            return when (max) {
                leftPct -> "LEFT"
                centerPct -> "CENTER"
                rightPct -> "RIGHT"
                else -> "NONE"
            }
        }
    }
    
    private enum class Partition {
        LEFT, CENTER, RIGHT
    }
}
