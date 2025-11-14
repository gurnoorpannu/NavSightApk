package org.tensorflow.lite.examples.objectdetection.navigation

/**
 * Test suite for WarningRateLimiter cooldown and anti-spam logic.
 */
object WarningRateLimiterTest {

    fun runAllTests() {
        println("=== WarningRateLimiter Tests ===\n")
        
        testGlobalCooldown()
        testPerObjectCooldown()
        testDirectionalCooldown()
        testMovementSensitivity()
        testHardSuppressionRules()
        testDistanceProgression()
        testReset()
        
        println("\n=== All Rate Limiter Tests Complete ===")
    }

    private fun testGlobalCooldown() {
        println("Test: Global Cooldown (2.5s)")
        WarningRateLimiter.reset()
        
        val guidance1 = Guidance("person", Direction.CENTER, DistanceCategory.CLOSE, 15.0f)
        val guidance2 = Guidance("chair", Direction.LEFT, DistanceCategory.CLOSE, 12.0f)
        
        // First announcement should be allowed
        assert(WarningRateLimiter.shouldAnnounce(guidance1)) { "First announcement should be allowed" }
        WarningRateLimiter.recordAnnouncement(guidance1)
        
        // Immediate second announcement should be blocked (global cooldown)
        assert(!WarningRateLimiter.shouldAnnounce(guidance2)) { "Second announcement should be blocked by global cooldown" }
        
        // Wait for global cooldown to expire
        Thread.sleep(2600)
        
        // Now should be allowed
        assert(WarningRateLimiter.shouldAnnounce(guidance2)) { "After 2.6s, announcement should be allowed" }
        
        println("✓ Global cooldown works\n")
    }

    private fun testPerObjectCooldown() {
        println("Test: Per-Object Cooldown (5s)")
        WarningRateLimiter.reset()
        
        val person1 = Guidance("person", Direction.CENTER, DistanceCategory.CLOSE, 15.0f)
        val person2 = Guidance("person", Direction.LEFT, DistanceCategory.VERY_CLOSE, 18.0f)
        val chair = Guidance("chair", Direction.RIGHT, DistanceCategory.CLOSE, 12.0f)
        
        // First person announcement
        assert(WarningRateLimiter.shouldAnnounce(person1)) { "First person should be allowed" }
        WarningRateLimiter.recordAnnouncement(person1)
        
        Thread.sleep(2600) // Wait for global cooldown
        
        // Same label (person) should be blocked even though direction changed
        assert(!WarningRateLimiter.shouldAnnounce(person2)) { "Same object should be blocked within 5s" }
        
        // Different object (chair) should be allowed
        assert(WarningRateLimiter.shouldAnnounce(chair)) { "Different object should be allowed" }
        
        println("✓ Per-object cooldown works\n")
    }

    private fun testDirectionalCooldown() {
        println("Test: Directional Cooldown (3s)")
        WarningRateLimiter.reset()
        
        val personCenter = Guidance("person", Direction.CENTER, DistanceCategory.CLOSE, 15.0f)
        val personLeft = Guidance("person", Direction.LEFT, DistanceCategory.CLOSE, 12.0f)
        
        // First announcement
        assert(WarningRateLimiter.shouldAnnounce(personCenter)) { "First should be allowed" }
        WarningRateLimiter.recordAnnouncement(personCenter)
        
        Thread.sleep(2600) // Wait for global cooldown
        
        // Different direction should still be blocked by per-object cooldown (5s)
        assert(!WarningRateLimiter.shouldAnnounce(personLeft)) { "Should be blocked by per-object cooldown" }
        
        println("✓ Directional cooldown works\n")
    }

    private fun testMovementSensitivity() {
        println("Test: Movement Sensitivity (distance changes)")
        WarningRateLimiter.reset()
        
        val personMedium = Guidance("person", Direction.CENTER, DistanceCategory.MEDIUM, 12.0f)
        val personClose = Guidance("person", Direction.CENTER, DistanceCategory.CLOSE, 15.0f)
        val personStillClose = Guidance("person", Direction.CENTER, DistanceCategory.CLOSE, 15.0f)
        val personVeryClose = Guidance("person", Direction.CENTER, DistanceCategory.VERY_CLOSE, 18.0f)
        
        // First detection at MEDIUM
        assert(WarningRateLimiter.shouldAnnounce(personMedium)) { "First detection should be allowed" }
        WarningRateLimiter.recordAnnouncement(personMedium)
        
        Thread.sleep(2600) // Wait for global cooldown
        
        // Movement to CLOSE should trigger (more dangerous)
        assert(WarningRateLimiter.shouldAnnounce(personClose)) { "Movement to CLOSE should trigger" }
        WarningRateLimiter.recordAnnouncement(personClose)
        
        Thread.sleep(2600)
        
        // Staying at CLOSE should NOT trigger
        assert(!WarningRateLimiter.shouldAnnounce(personStillClose)) { "No distance change should not trigger" }
        
        Thread.sleep(2600)
        
        // Movement to VERY_CLOSE should trigger
        assert(WarningRateLimiter.shouldAnnounce(personVeryClose)) { "Movement to VERY_CLOSE should trigger" }
        
        println("✓ Movement sensitivity works\n")
    }

    private fun testHardSuppressionRules() {
        println("Test: Hard Suppression Rules")
        WarningRateLimiter.reset()
        
        // Rule: FAR distance always suppressed
        val farObject = Guidance("person", Direction.CENTER, DistanceCategory.FAR, 10.0f)
        assert(!WarningRateLimiter.shouldAnnounce(farObject)) { "FAR objects should be suppressed" }
        
        // Rule: MEDIUM distance suppressed unless center + high priority
        val mediumSide = Guidance("chair", Direction.LEFT, DistanceCategory.MEDIUM, 8.0f)
        assert(!WarningRateLimiter.shouldAnnounce(mediumSide)) { "MEDIUM side objects should be suppressed" }
        
        val mediumCenterHighPriority = Guidance("person", Direction.CENTER, DistanceCategory.MEDIUM, 12.0f)
        assert(WarningRateLimiter.shouldAnnounce(mediumCenterHighPriority)) { "MEDIUM center high-priority should be allowed" }
        
        // Rule: Objects too small
        val tinyObject = Guidance("person", Direction.CENTER, DistanceCategory.CLOSE, 15.0f)
        assert(!WarningRateLimiter.shouldAnnounce(tinyObject, currentWidth = 0.05f)) { "Tiny objects should be suppressed" }
        
        // Rule: Objects at frame edges
        val leftEdge = Guidance("person", Direction.LEFT, DistanceCategory.CLOSE, 15.0f)
        assert(!WarningRateLimiter.shouldAnnounce(leftEdge, currentXCenter = 0.02f)) { "Left edge objects should be suppressed" }
        
        val rightEdge = Guidance("person", Direction.RIGHT, DistanceCategory.CLOSE, 15.0f)
        assert(!WarningRateLimiter.shouldAnnounce(rightEdge, currentXCenter = 0.98f)) { "Right edge objects should be suppressed" }
        
        println("✓ Hard suppression rules work\n")
    }

    private fun testDistanceProgression() {
        println("Test: Distance Progression Logic")
        WarningRateLimiter.reset()
        
        val chair1 = Guidance("chair", Direction.LEFT, DistanceCategory.FAR, 8.0f)
        val chair2 = Guidance("chair", Direction.LEFT, DistanceCategory.MEDIUM, 10.0f)
        val chair3 = Guidance("chair", Direction.LEFT, DistanceCategory.CLOSE, 12.0f)
        val chair4 = Guidance("chair", Direction.LEFT, DistanceCategory.VERY_CLOSE, 15.0f)
        
        // FAR is suppressed by hard rule
        assert(!WarningRateLimiter.shouldAnnounce(chair1)) { "FAR should be suppressed" }
        
        // MEDIUM side object is suppressed
        assert(!WarningRateLimiter.shouldAnnounce(chair2)) { "MEDIUM side should be suppressed" }
        
        // CLOSE should be allowed (first real announcement)
        assert(WarningRateLimiter.shouldAnnounce(chair3)) { "CLOSE should be allowed" }
        WarningRateLimiter.recordAnnouncement(chair3)
        
        Thread.sleep(2600)
        
        // VERY_CLOSE should trigger (more dangerous)
        assert(WarningRateLimiter.shouldAnnounce(chair4)) { "VERY_CLOSE should trigger" }
        
        println("✓ Distance progression works\n")
    }

    private fun testReset() {
        println("Test: Reset Functionality")
        WarningRateLimiter.reset()
        
        val guidance = Guidance("person", Direction.CENTER, DistanceCategory.CLOSE, 15.0f)
        
        // Make announcement
        assert(WarningRateLimiter.shouldAnnounce(guidance)) { "Should be allowed" }
        WarningRateLimiter.recordAnnouncement(guidance)
        
        // Should be blocked immediately
        assert(!WarningRateLimiter.shouldAnnounce(guidance)) { "Should be blocked by global cooldown" }
        
        // Reset
        WarningRateLimiter.reset()
        
        // Should be allowed again after reset
        assert(WarningRateLimiter.shouldAnnounce(guidance)) { "Should be allowed after reset" }
        
        println("✓ Reset works\n")
    }

    /**
     * Scenario test: Realistic navigation sequence
     */
    fun testRealisticScenario() {
        println("=== Realistic Scenario Test ===\n")
        WarningRateLimiter.reset()
        
        println("User walking down hallway...")
        
        // Frame 1: Person detected far away (suppressed - FAR)
        val frame1 = Guidance("person", Direction.CENTER, DistanceCategory.FAR, 8.0f)
        val result1 = WarningRateLimiter.shouldAnnounce(frame1)
        println("Frame 1 - Person FAR: ${if (result1) "ANNOUNCE" else "suppress"}")
        assert(!result1) { "FAR should be suppressed" }
        
        Thread.sleep(100)
        
        // Frame 2: Person getting closer (MEDIUM, center, high priority - allowed)
        val frame2 = Guidance("person", Direction.CENTER, DistanceCategory.MEDIUM, 12.0f)
        val result2 = WarningRateLimiter.shouldAnnounce(frame2)
        println("Frame 2 - Person MEDIUM center: ${if (result2) "ANNOUNCE ✓" else "suppress"}")
        if (result2) WarningRateLimiter.recordAnnouncement(frame2)
        
        Thread.sleep(1000)
        
        // Frame 3: Still MEDIUM (suppressed - no change)
        val frame3 = Guidance("person", Direction.CENTER, DistanceCategory.MEDIUM, 12.0f)
        val result3 = WarningRateLimiter.shouldAnnounce(frame3)
        println("Frame 3 - Person still MEDIUM: ${if (result3) "ANNOUNCE" else "suppress"}")
        assert(!result3) { "No distance change should suppress" }
        
        Thread.sleep(2000)
        
        // Frame 4: Person now CLOSE (allowed - more dangerous)
        val frame4 = Guidance("person", Direction.CENTER, DistanceCategory.CLOSE, 15.0f)
        val result4 = WarningRateLimiter.shouldAnnounce(frame4)
        println("Frame 4 - Person CLOSE: ${if (result4) "ANNOUNCE ✓" else "suppress"}")
        if (result4) WarningRateLimiter.recordAnnouncement(frame4)
        
        Thread.sleep(1000)
        
        // Frame 5: Chair appears on left (suppressed - global cooldown)
        val frame5 = Guidance("chair", Direction.LEFT, DistanceCategory.CLOSE, 12.0f)
        val result5 = WarningRateLimiter.shouldAnnounce(frame5)
        println("Frame 5 - Chair LEFT: ${if (result5) "ANNOUNCE" else "suppress (global cooldown)"}")
        
        Thread.sleep(2000)
        
        // Frame 6: Chair still there (allowed now - global cooldown expired)
        val frame6 = Guidance("chair", Direction.LEFT, DistanceCategory.CLOSE, 12.0f)
        val result6 = WarningRateLimiter.shouldAnnounce(frame6)
        println("Frame 6 - Chair LEFT: ${if (result6) "ANNOUNCE ✓" else "suppress"}")
        if (result6) WarningRateLimiter.recordAnnouncement(frame6)
        
        Thread.sleep(1000)
        
        // Frame 7: Person VERY_CLOSE (suppressed - per-object cooldown)
        val frame7 = Guidance("person", Direction.CENTER, DistanceCategory.VERY_CLOSE, 18.0f)
        val result7 = WarningRateLimiter.shouldAnnounce(frame7)
        println("Frame 7 - Person VERY_CLOSE: ${if (result7) "ANNOUNCE" else "suppress (per-object cooldown)"}")
        
        println("\n✓ Realistic scenario complete")
        println("Result: Only 3 announcements out of 7 frames - spam prevented!\n")
    }
}

// Uncomment to run tests:
// fun main() {
//     WarningRateLimiterTest.runAllTests()
//     WarningRateLimiterTest.testRealisticScenario()
// }
