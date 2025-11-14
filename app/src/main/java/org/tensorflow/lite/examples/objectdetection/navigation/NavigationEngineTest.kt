package org.tensorflow.lite.examples.objectdetection.navigation

/**
 * Simple test examples for NavigationEngine.
 * These can be run manually or converted to JUnit tests.
 */
object NavigationEngineTest {

    fun runAllTests() {
        println("=== NavigationEngine Tests ===\n")
        
        testBasicDetection()
        testFiltering()
        testDirectionDetection()
        testDistanceEstimation()
        testPriorityRanking()
        testStoplist()
        testMultipleObjects()
        
        println("\n=== All Tests Complete ===")
    }

    private fun testBasicDetection() {
        println("Test: Basic Detection")
        val engine = NavigationEngine()
        
        val detection = NavigationDetection(
            label = "person",
            confidence = 0.85f,
            xCenter = 0.5f,
            yCenter = 0.7f,
            width = 0.4f,
            height = 0.6f
        )
        
        val guidance = engine.analyzeDetections(listOf(detection))
        
        assert(guidance != null) { "Should return guidance" }
        assert(guidance?.label == "person") { "Label should be 'person'" }
        assert(guidance?.direction == Direction.CENTER) { "Should be CENTER" }
        assert(guidance?.distance == DistanceCategory.CLOSE) { "Should be CLOSE" }
        
        println("✓ Basic detection works\n")
    }

    private fun testFiltering() {
        println("Test: Filtering")
        val engine = NavigationEngine()
        
        // Low confidence - should be filtered
        val lowConfidence = NavigationDetection(
            label = "person",
            confidence = 0.3f,
            xCenter = 0.5f,
            yCenter = 0.7f,
            width = 0.4f,
            height = 0.6f
        )
        
        // Upper half - should be filtered
        val upperHalf = NavigationDetection(
            label = "person",
            confidence = 0.8f,
            xCenter = 0.5f,
            yCenter = 0.3f,
            width = 0.4f,
            height = 0.6f
        )
        
        // Too small - should be filtered
        val tooSmall = NavigationDetection(
            label = "person",
            confidence = 0.8f,
            xCenter = 0.5f,
            yCenter = 0.7f,
            width = 0.03f,
            height = 0.04f
        )
        
        assert(engine.analyzeDetections(listOf(lowConfidence)) == null) { "Low confidence should be filtered" }
        assert(engine.analyzeDetections(listOf(upperHalf)) == null) { "Upper half should be filtered" }
        assert(engine.analyzeDetections(listOf(tooSmall)) == null) { "Too small should be filtered" }
        
        println("✓ Filtering works correctly\n")
    }

    private fun testDirectionDetection() {
        println("Test: Direction Detection")
        val engine = NavigationEngine()
        
        val leftDetection = NavigationDetection("person", 0.8f, 0.2f, 0.7f, 0.3f, 0.5f)
        val centerDetection = NavigationDetection("person", 0.8f, 0.5f, 0.7f, 0.3f, 0.5f)
        val rightDetection = NavigationDetection("person", 0.8f, 0.8f, 0.7f, 0.3f, 0.5f)
        
        val leftGuidance = engine.analyzeDetections(listOf(leftDetection))
        val centerGuidance = engine.analyzeDetections(listOf(centerDetection))
        val rightGuidance = engine.analyzeDetections(listOf(rightDetection))
        
        assert(leftGuidance?.direction == Direction.LEFT) { "Should be LEFT" }
        assert(centerGuidance?.direction == Direction.CENTER) { "Should be CENTER" }
        assert(rightGuidance?.direction == Direction.RIGHT) { "Should be RIGHT" }
        
        println("✓ Direction detection works\n")
    }

    private fun testDistanceEstimation() {
        println("Test: Distance Estimation")
        val engine = NavigationEngine()
        
        val veryClose = NavigationDetection("person", 0.8f, 0.5f, 0.7f, 0.7f, 0.8f)
        val close = NavigationDetection("person", 0.8f, 0.5f, 0.7f, 0.4f, 0.5f)
        val medium = NavigationDetection("person", 0.8f, 0.5f, 0.7f, 0.2f, 0.3f)
        val far = NavigationDetection("person", 0.8f, 0.5f, 0.7f, 0.08f, 0.1f)
        
        val veryCloseGuidance = engine.analyzeDetections(listOf(veryClose))
        val closeGuidance = engine.analyzeDetections(listOf(close))
        val mediumGuidance = engine.analyzeDetections(listOf(medium))
        val farGuidance = engine.analyzeDetections(listOf(far))
        
        assert(veryCloseGuidance?.distance == DistanceCategory.VERY_CLOSE) { "Should be VERY_CLOSE" }
        assert(closeGuidance?.distance == DistanceCategory.CLOSE) { "Should be CLOSE" }
        assert(mediumGuidance?.distance == DistanceCategory.MEDIUM) { "Should be MEDIUM" }
        assert(farGuidance?.distance == DistanceCategory.FAR) { "Should be FAR" }
        
        println("✓ Distance estimation works\n")
    }

    private fun testPriorityRanking() {
        println("Test: Priority Ranking")
        val engine = NavigationEngine()
        
        // Center + close should win over left + far
        val centerClose = NavigationDetection("person", 0.8f, 0.5f, 0.7f, 0.4f, 0.5f)
        val leftFar = NavigationDetection("chair", 0.8f, 0.2f, 0.7f, 0.1f, 0.2f)
        
        val guidance = engine.analyzeDetections(listOf(centerClose, leftFar))
        
        assert(guidance?.label == "person") { "Center+close should have higher priority" }
        assert(guidance?.direction == Direction.CENTER) { "Should prioritize center" }
        
        println("✓ Priority ranking works\n")
    }

    private fun testStoplist() {
        println("Test: Stoplist Filtering")
        val engine = NavigationEngine()
        
        val book = NavigationDetection("book", 0.8f, 0.5f, 0.7f, 0.3f, 0.4f)
        val bottle = NavigationDetection("bottle", 0.8f, 0.5f, 0.7f, 0.3f, 0.4f)
        val cup = NavigationDetection("cup", 0.8f, 0.5f, 0.7f, 0.3f, 0.4f)
        val keyboard = NavigationDetection("keyboard", 0.8f, 0.5f, 0.7f, 0.3f, 0.4f)
        
        assert(engine.analyzeDetections(listOf(book)) == null) { "Book should be filtered" }
        assert(engine.analyzeDetections(listOf(bottle)) == null) { "Bottle should be filtered" }
        assert(engine.analyzeDetections(listOf(cup)) == null) { "Cup should be filtered" }
        assert(engine.analyzeDetections(listOf(keyboard)) == null) { "Keyboard should be filtered" }
        
        println("✓ Stoplist filtering works\n")
    }

    private fun testMultipleObjects() {
        println("Test: Multiple Objects")
        val engine = NavigationEngine()
        
        val detections = listOf(
            NavigationDetection("person", 0.9f, 0.5f, 0.7f, 0.5f, 0.6f),  // Center, close, high priority
            NavigationDetection("chair", 0.7f, 0.2f, 0.8f, 0.2f, 0.3f),   // Left, medium
            NavigationDetection("car", 0.6f, 0.8f, 0.7f, 0.15f, 0.2f),    // Right, far
            NavigationDetection("cup", 0.8f, 0.5f, 0.6f, 0.1f, 0.1f)      // Filtered (stoplist)
        )
        
        val guidance = engine.analyzeDetections(detections)
        
        assert(guidance != null) { "Should return guidance" }
        assert(guidance?.label == "person") { "Person should have highest priority" }
        assert(guidance?.direction == Direction.CENTER) { "Should be center" }
        
        println("✓ Multiple object handling works\n")
    }

    /**
     * Example of how to format test output for debugging
     */
    fun printGuidance(guidance: Guidance?) {
        if (guidance == null) {
            println("No guidance (all detections filtered)")
            return
        }
        
        println("Guidance Output:")
        println("  Label: ${guidance.label}")
        println("  Direction: ${guidance.direction}")
        println("  Distance: ${guidance.distance}")
        println("  Priority: %.2f".format(guidance.priority))
        
        val announcement = NavigationEngineExample.guidanceToAnnouncement(guidance)
        println("  Announcement: \"$announcement\"")
    }
}

// Uncomment to run tests manually:
// fun main() {
//     NavigationEngineTest.runAllTests()
// }
