package org.tensorflow.lite.examples.objectdetection.navigation

import android.util.Log

/**
 * Manual test scenarios for ClosestObjectSpeaker
 * Run these scenarios to verify the behavior
 */
object ClosestObjectSpeakerTest {
    
    private const val TAG = "ClosestObjectTest"
    
    /**
     * Test Scenario 1: Single object detection
     * Expected: Speaks "person, about 2.5 meters ahead"
     */
    fun testSingleObject(): List<NavigationDetection> {
        Log.d(TAG, "=== Test 1: Single Object ===")
        return listOf(
            NavigationDetection(
                label = "person",
                confidence = 0.85f,
                xCenter = 0.5f,  // Center
                yCenter = 0.5f,
                width = 0.2f,
                height = 0.3f,
                depthValue = 0.4f,
                distanceMeters = 2.5f
            )
        )
    }
    
    /**
     * Test Scenario 2: Multiple objects - closest should be selected
     * Expected: Speaks "chair, about 1.2 meters to your left" (ignores person at 3.5m)
     */
    fun testMultipleObjects(): List<NavigationDetection> {
        Log.d(TAG, "=== Test 2: Multiple Objects - Select Closest ===")
        return listOf(
            NavigationDetection(
                label = "person",
                confidence = 0.90f,
                xCenter = 0.5f,
                yCenter = 0.5f,
                width = 0.15f,
                height = 0.25f,
                depthValue = 0.7f,
                distanceMeters = 3.5f  // Farther
            ),
            NavigationDetection(
                label = "chair",
                confidence = 0.75f,
                xCenter = 0.2f,  // Left side
                yCenter = 0.6f,
                width = 0.18f,
                height = 0.22f,
                depthValue = 0.2f,
                distanceMeters = 1.2f  // CLOSEST - should be selected
            ),
            NavigationDetection(
                label = "table",
                confidence = 0.65f,
                xCenter = 0.8f,  // Right side
                yCenter = 0.5f,
                width = 0.25f,
                height = 0.20f,
                depthValue = 0.5f,
                distanceMeters = 2.8f  // Farther
            )
        )
    }
    
    /**
     * Test Scenario 3: Low confidence object should be filtered
     * Expected: Speaks "car, about 4.0 meters to your right" (ignores person at 0.35 confidence)
     */
    fun testLowConfidenceFiltering(): List<NavigationDetection> {
        Log.d(TAG, "=== Test 3: Low Confidence Filtering ===")
        return listOf(
            NavigationDetection(
                label = "person",
                confidence = 0.35f,  // Below 0.40 threshold - should be IGNORED
                xCenter = 0.5f,
                yCenter = 0.5f,
                width = 0.15f,
                height = 0.25f,
                depthValue = 0.15f,
                distanceMeters = 1.0f  // Closest but low confidence
            ),
            NavigationDetection(
                label = "car",
                confidence = 0.88f,  // Above threshold
                xCenter = 0.85f,  // Right side
                yCenter = 0.5f,
                width = 0.30f,
                height = 0.25f,
                depthValue = 0.8f,
                distanceMeters = 4.0f  // Should be selected
            )
        )
    }
    
    /**
     * Test Scenario 4: Direction detection
     * Expected: 
     * - Left object: "bottle, about 1.5 meters to your left"
     * - Center object: "person, about 2.0 meters ahead"
     * - Right object: "cup, about 1.8 meters to your right"
     */
    fun testDirectionDetection(): Map<String, NavigationDetection> {
        Log.d(TAG, "=== Test 4: Direction Detection ===")
        return mapOf(
            "left" to NavigationDetection(
                label = "bottle",
                confidence = 0.80f,
                xCenter = 0.15f,  // < 1/3 = LEFT
                yCenter = 0.5f,
                width = 0.1f,
                height = 0.15f,
                depthValue = 0.3f,
                distanceMeters = 1.5f
            ),
            "center" to NavigationDetection(
                label = "person",
                confidence = 0.90f,
                xCenter = 0.5f,  // Between 1/3 and 2/3 = CENTER
                yCenter = 0.5f,
                width = 0.2f,
                height = 0.3f,
                depthValue = 0.4f,
                distanceMeters = 2.0f
            ),
            "right" to NavigationDetection(
                label = "cup",
                confidence = 0.75f,
                xCenter = 0.85f,  // > 2/3 = RIGHT
                yCenter = 0.5f,
                width = 0.08f,
                height = 0.12f,
                depthValue = 0.35f,
                distanceMeters = 1.8f
            )
        )
    }
    
    /**
     * Test Scenario 5: Objects without depth should be ignored
     * Expected: Speaks "chair, about 2.0 meters ahead" (ignores person without depth)
     */
    fun testNoDepthFiltering(): List<NavigationDetection> {
        Log.d(TAG, "=== Test 5: No Depth Filtering ===")
        return listOf(
            NavigationDetection(
                label = "person",
                confidence = 0.90f,
                xCenter = 0.5f,
                yCenter = 0.5f,
                width = 0.2f,
                height = 0.3f,
                depthValue = null,  // No depth
                distanceMeters = null  // No distance - should be IGNORED
            ),
            NavigationDetection(
                label = "chair",
                confidence = 0.75f,
                xCenter = 0.5f,
                yCenter = 0.6f,
                width = 0.18f,
                height = 0.22f,
                depthValue = 0.4f,
                distanceMeters = 2.0f  // Has depth - should be selected
            )
        )
    }
    
    /**
     * Print test summary
     */
    fun printTestSummary() {
        Log.d(TAG, """
            ╔════════════════════════════════════════════════════════════╗
            ║         CLOSEST OBJECT SPEAKER TEST SCENARIOS              ║
            ╠════════════════════════════════════════════════════════════╣
            ║ Test 1: Single object detection                            ║
            ║ Test 2: Multiple objects - select closest                  ║
            ║ Test 3: Low confidence filtering (< 0.40)                  ║
            ║ Test 4: Direction detection (left/center/right)            ║
            ║ Test 5: Objects without depth are ignored                  ║
            ╠════════════════════════════════════════════════════════════╣
            ║ VERIFICATION CHECKLIST:                                    ║
            ║ ✓ Only closest object is spoken                            ║
            ║ ✓ Confidence >= 0.40 required                              ║
            ║ ✓ Uses distanceMeters from MiDaS                           ║
            ║ ✓ Direction based on centerX position                      ║
            ║ ✓ Objects without depth are filtered out                   ║
            ║ ✓ EMA smoothing prevents jitter                            ║
            ║ ✓ Hysteresis: >0.3m change or label change                 ║
            ║ ✓ Cooldown: 1200ms between announcements                   ║
            ╚════════════════════════════════════════════════════════════╝
        """.trimIndent())
    }
}
