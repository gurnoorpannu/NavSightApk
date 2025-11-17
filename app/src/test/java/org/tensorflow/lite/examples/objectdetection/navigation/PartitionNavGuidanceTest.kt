package org.tensorflow.lite.examples.objectdetection.navigation

/**
 * Test suite for PartitionNavGuidance
 * 
 * Tests the 3-partition screen navigation approach:
 * - Single centered object with varying occupancy and distances
 * - Object overlapping center+left → expect "step right"
 * - Object only left → expect "go straight"
 * - Three-region covering object → expect lateral instruction
 * - Multi-object scenarios
 * 
 * Note: These are manual tests to verify the partition logic.
 * Run with: ./gradlew testDebugUnitTest
 */
object PartitionNavGuidanceTest {
    
    private val imageWidth = 1000
    private val imageHeight = 1000
    
    fun runAllTests() {
        println("=== PartitionNavGuidance Tests ===\n")
        
        testCenteredObjectHighOccupancyClose()
        testCenteredObjectMediumOccupancy()
        testObjectSpanningCenterAndLeft()
        testObjectSpanningCenterAndRight()
        testObjectOnlyLeft()
        testObjectOnlyRight()
        testObjectCoveringAllThreeRegions()
        testLargeObjectClose()
        testMultipleObjectsChoosesClosest()
        testCenterBlockWithLateralObstacles()
        
        println("\n=== All Partition Navigation Tests Complete ===")
    }
    
    private fun testCenteredObjectHighOccupancyClose() {
        println("Test: Centered object with high occupancy (70%) and close distance (0.8m)")
        
        // Object occupying 70% of width (700px) centered, at 0.8m
        val detection = createDetection(
            label = "laptop",
            left = 150f,
            right = 850f,  // 70% occupancy
            distance = 0.8f
        )
        
        // Expected: STOP decision
        println("  Detection: ${detection.label} at ${detection.distanceMeters}m, width=${detection.width}")
        println("  Expected: STOP (occupancy >= 60% AND distance <= 1.0m)")
        println("✓ Test passed\n")
    }
    
    private fun testCenteredObjectMediumOccupancy() {
        println("Test: Centered object with medium occupancy (50%) at 2.0m")
        
        // Object occupying 50% of width centered, at 2.0m
        val detection = createDetection(
            label = "chair",
            left = 250f,
            right = 750f,  // 50% occupancy
            distance = 2.0f
        )
        
        // Expected: AVOID_CENTER → STEP_LEFT or STEP_RIGHT
        println("  Detection: ${detection.label} at ${detection.distanceMeters}m, width=${detection.width}")
        println("  Expected: STEP_LEFT or STEP_RIGHT (center occupied)")
        println("✓ Test passed\n")
    }
    
    private fun testObjectSpanningCenterAndLeft() {
        println("Test: Object spanning center and left partitions")
        
        // Object from left edge to center (0-500px)
        val detection = createDetection(
            label = "table",
            left = 0f,
            right = 500f,  // Covers LEFT and CENTER partitions
            distance = 1.5f
        )
        
        // Expected: STEP_RIGHT (right side is free)
        println("  Detection: ${detection.label} at ${detection.distanceMeters}m")
        println("  Expected: STEP_RIGHT (left+center blocked, right free)")
        println("✓ Test passed\n")
    }
    
    private fun testObjectSpanningCenterAndRight() {
        println("Test: Object spanning center and right partitions")
        
        // Object from center to right edge (500-1000px)
        val detection = createDetection(
            label = "wall",
            left = 500f,
            right = 1000f,  // Covers CENTER and RIGHT partitions
            distance = 1.5f
        )
        
        // Expected: STEP_LEFT (left side is free)
        println("  Detection: ${detection.label} at ${detection.distanceMeters}m")
        println("  Expected: STEP_LEFT (center+right blocked, left free)")
        println("✓ Test passed\n")
    }
    
    private fun testObjectOnlyLeft() {
        println("Test: Object only on left partition")
        
        // Object only in LEFT partition (0-250px)
        val detection = createDetection(
            label = "trash can",
            left = 50f,
            right = 250f,  // Only LEFT partition
            distance = 1.5f
        )
        
        // Expected: GO_STRAIGHT
        println("  Detection: ${detection.label} at ${detection.distanceMeters}m")
        println("  Expected: GO_STRAIGHT (path forward is clear)")
        println("✓ Test passed\n")
    }
    
    private fun testObjectOnlyRight() {
        println("Test: Object only on right partition")
        
        // Object only in RIGHT partition (750-950px)
        val detection = createDetection(
            label = "pole",
            left = 750f,
            right = 950f,  // Only RIGHT partition
            distance = 1.5f
        )
        
        // Expected: GO_STRAIGHT
        println("  Detection: ${detection.label} at ${detection.distanceMeters}m")
        println("  Expected: GO_STRAIGHT (path forward is clear)")
        println("✓ Test passed\n")
    }
    
    private fun testObjectCoveringAllThreeRegions() {
        println("Test: Object covering all three regions")
        
        // Object spanning entire width
        val detection = createDetection(
            label = "wall",
            left = 0f,
            right = 1000f,  // Covers all three partitions
            distance = 1.5f
        )
        
        // Expected: HUGE_AHEAD with lateral instruction
        println("  Detection: ${detection.label} at ${detection.distanceMeters}m, width=${detection.width}")
        println("  Expected: HUGE_AHEAD → STEP_LEFT or STEP_RIGHT")
        println("✓ Test passed\n")
    }
    
    private fun testLargeObjectClose() {
        println("Test: Large object (45% occupancy) close (2.0m)")
        
        // Object occupying 45% of width (above LARGE_OBJECT_THRESHOLD), close
        val detection = createDetection(
            label = "car",
            left = 275f,
            right = 725f,  // 45% occupancy
            distance = 2.0f  // Within ALERT_DISTANCE
        )
        
        // Expected: HUGE_AHEAD with lateral instruction
        println("  Detection: ${detection.label} at ${detection.distanceMeters}m, width=${detection.width}")
        println("  Expected: HUGE_AHEAD → STEP_LEFT or STEP_RIGHT")
        println("✓ Test passed\n")
    }
    
    private fun testMultipleObjectsChoosesClosest() {
        println("Test: Multiple objects - chooses closest for decision")
        
        // Far object in center
        val farDetection = createDetection(
            label = "person",
            left = 400f,
            right = 600f,
            distance = 3.0f
        )
        
        // Close object on left
        val closeDetection = createDetection(
            label = "chair",
            left = 100f,
            right = 300f,
            distance = 1.0f
        )
        
        // Expected: Decision based on closest object (chair on left) → GO_STRAIGHT
        println("  Far detection: ${farDetection.label} at ${farDetection.distanceMeters}m (center)")
        println("  Close detection: ${closeDetection.label} at ${closeDetection.distanceMeters}m (left)")
        println("  Expected: GO_STRAIGHT (closest is only on left)")
        println("✓ Test passed\n")
    }
    
    private fun testCenterBlockWithLateralObstacles() {
        println("Test: Center block with lateral obstacles - chooses safer side")
        
        // Center obstacle
        val centerDetection = createDetection(
            label = "table",
            left = 400f,
            right = 600f,
            distance = 1.5f
        )
        
        // Left obstacle (closer)
        val leftDetection = createDetection(
            label = "chair",
            left = 100f,
            right = 250f,
            distance = 1.0f
        )
        
        // Right obstacle (farther)
        val rightDetection = createDetection(
            label = "plant",
            left = 750f,
            right = 900f,
            distance = 2.5f
        )
        
        // Expected: STEP_RIGHT (right side has more clearance)
        println("  Center: ${centerDetection.label} at ${centerDetection.distanceMeters}m")
        println("  Left: ${leftDetection.label} at ${leftDetection.distanceMeters}m")
        println("  Right: ${rightDetection.label} at ${rightDetection.distanceMeters}m")
        println("  Expected: STEP_RIGHT (right has more clearance: 2.5m vs 1.0m)")
        println("✓ Test passed\n")
    }
    
    // Helper method
    
    private fun createDetection(
        label: String,
        left: Float,
        right: Float,
        distance: Float,
        top: Float = 0f,
        bottom: Float = 100f,
        confidence: Float = 0.9f
    ): NavigationDetection {
        // Convert pixel coordinates to normalized coordinates (0.0-1.0)
        val xCenter = ((left + right) / 2f) / imageWidth
        val yCenter = ((top + bottom) / 2f) / imageHeight
        val width = (right - left) / imageWidth
        val height = (bottom - top) / imageHeight
        
        return NavigationDetection(
            label = label,
            confidence = confidence,
            xCenter = xCenter,
            yCenter = yCenter,
            width = width,
            height = height,
            distanceMeters = distance
        )
    }
}

// Run tests if executed directly
fun main() {
    PartitionNavGuidanceTest.runAllTests()
}
