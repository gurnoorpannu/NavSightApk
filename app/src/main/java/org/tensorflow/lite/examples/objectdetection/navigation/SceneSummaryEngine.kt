package org.tensorflow.lite.examples.objectdetection.navigation

import android.util.Log

/**
 * SceneSummaryEngine - Multi-object scene summarization
 * 
 * Analyzes all detections and produces a natural language summary:
 * - "Two people ahead and a chair slightly right"
 * - "A table on your right and an open path in front"
 * - "Multiple obstacles ahead — adjust path"
 * 
 * This summary is triggered:
 * - On user request (button/gesture/voice)
 * - Automatically every 12 seconds if scene changes >40%
 */
class SceneSummaryEngine {
    
    companion object {
        private const val TAG = "SceneSummary"
        
        // Scene change detection
        private const val SCENE_CHANGE_THRESHOLD = 0.4f // 40% change
        private const val AUTO_SUMMARY_INTERVAL_MS = 12000L // 12 seconds
        
        // Distance thresholds
        private const val MEDIUM_WIDTH = 0.15f
        
        // Direction boundaries
        private const val LEFT_BOUNDARY = 0.33f
        private const val RIGHT_BOUNDARY = 0.66f
        
        // Minimum confidence
        private const val MIN_CONFIDENCE = 0.40f
    }
    
    private var lastSummaryTime = 0L
    private var lastSceneSignature = ""
    
    /**
     * Generates a natural language summary of the current scene
     * 
     * @param detections List of navigation detections
     * @return Human-readable scene summary
     */
    fun generateSummary(detections: List<NavigationDetection>): String {
        // Filter valid obstacles
        val validObstacles = detections.filter {
            it.confidence >= MIN_CONFIDENCE &&
            it.width >= MEDIUM_WIDTH && // Ignore FAR objects
            it.yCenter >= 0.5f // Only lower half
        }
        
        if (validObstacles.isEmpty()) {
            return "Path clear, no obstacles detected"
        }
        
        // Group by direction
        val leftObjects = validObstacles.filter { it.xCenter < LEFT_BOUNDARY }
        val centerObjects = validObstacles.filter { it.xCenter >= LEFT_BOUNDARY && it.xCenter <= RIGHT_BOUNDARY }
        val rightObjects = validObstacles.filter { it.xCenter > RIGHT_BOUNDARY }
        
        // Build summary parts
        val summaryParts = mutableListOf<String>()
        
        // Center (most important)
        if (centerObjects.isNotEmpty()) {
            summaryParts.add(summarizeGroup(centerObjects, "ahead"))
        }
        
        // Left
        if (leftObjects.isNotEmpty()) {
            summaryParts.add(summarizeGroup(leftObjects, "on your left"))
        }
        
        // Right
        if (rightObjects.isNotEmpty()) {
            summaryParts.add(summarizeGroup(rightObjects, "on your right"))
        }
        
        // Combine parts
        val summary = when (summaryParts.size) {
            0 -> "Path clear"
            1 -> summaryParts[0]
            2 -> "${summaryParts[0]} and ${summaryParts[1]}"
            else -> "${summaryParts[0]}, ${summaryParts[1]}, and ${summaryParts[2]}"
        }
        
        Log.d(TAG, "Scene summary: $summary")
        return summary
    }
    
    /**
     * Summarizes a group of objects in a specific direction
     */
    private fun summarizeGroup(objects: List<NavigationDetection>, direction: String): String {
        if (objects.isEmpty()) return ""
        
        // Count by label
        val labelCounts = objects.groupingBy { it.label }.eachCount()
        
        return when {
            // Multiple objects of same type
            labelCounts.size == 1 && objects.size > 1 -> {
                val label = objects.first().label
                val count = objects.size
                "$count ${pluralize(label, count)} $direction"
            }
            
            // Multiple different objects
            objects.size > 3 -> {
                "multiple obstacles $direction"
            }
            
            // 2-3 objects, list them
            objects.size <= 3 -> {
                val labels = labelCounts.entries
                    .sortedByDescending { it.value }
                    .take(2)
                    .map { (label, count) ->
                        if (count > 1) "$count ${pluralize(label, count)}" else "a $label"
                    }
                
                when (labels.size) {
                    1 -> "${labels[0]} $direction"
                    2 -> "${labels[0]} and ${labels[1]} $direction"
                    else -> labels.joinToString(", ") + " $direction"
                }
            }
            
            // Single object
            else -> {
                "a ${objects.first().label} $direction"
            }
        }
    }
    
    /**
     * Simple pluralization helper
     */
    private fun pluralize(label: String, count: Int): String {
        if (count <= 1) return label
        
        return when {
            label.endsWith("person") -> label.replace("person", "people")
            label.endsWith("s") -> label
            label.endsWith("ch") || label.endsWith("sh") -> "${label}es"
            else -> "${label}s"
        }
    }
    
    /**
     * Checks if enough time has passed and scene has changed significantly
     * 
     * @param detections Current detections
     * @return true if summary should be triggered automatically
     */
    fun shouldAutoSummarize(detections: List<NavigationDetection>): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Check time interval
        if (currentTime - lastSummaryTime < AUTO_SUMMARY_INTERVAL_MS) {
            return false
        }
        
        // Check scene change
        val currentSignature = generateSceneSignature(detections)
        val hasChanged = hasSceneChanged(currentSignature)
        
        if (hasChanged) {
            lastSummaryTime = currentTime
            lastSceneSignature = currentSignature
            return true
        }
        
        return false
    }
    
    /**
     * Generates a signature for scene change detection
     */
    private fun generateSceneSignature(detections: List<NavigationDetection>): String {
        return detections
            .filter { it.confidence >= MIN_CONFIDENCE }
            .sortedByDescending { it.confidence }
            .take(5)
            .joinToString(",") { "${it.label}:${String.format("%.2f", it.confidence)}" }
    }
    
    /**
     * Checks if scene has changed significantly using Jaccard similarity
     */
    private fun hasSceneChanged(newSignature: String): Boolean {
        if (lastSceneSignature.isEmpty()) return true
        
        val oldLabels = lastSceneSignature.split(",").map { it.split(":")[0] }.toSet()
        val newLabels = newSignature.split(",").map { it.split(":")[0] }.toSet()
        
        if (oldLabels.isEmpty() && newLabels.isEmpty()) return false
        if (oldLabels.isEmpty() || newLabels.isEmpty()) return true
        
        // Calculate Jaccard similarity
        val intersection = oldLabels.intersect(newLabels).size
        val union = oldLabels.union(newLabels).size
        val similarity = intersection.toFloat() / union.toFloat()
        
        return similarity < (1.0f - SCENE_CHANGE_THRESHOLD)
    }
    
    /**
     * Resets the scene tracking state
     */
    fun reset() {
        lastSummaryTime = 0L
        lastSceneSignature = ""
    }
}
