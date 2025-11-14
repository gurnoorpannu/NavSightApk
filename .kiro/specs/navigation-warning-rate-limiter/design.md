# Design Document

## Overview

The WarningRateLimiter is a singleton object that implements intelligent anti-spam logic for navigation guidance announcements. It acts as a gatekeeper between the NavigationEngine's output and the TTS/UI layer, using multiple cooldown mechanisms and suppression rules to ensure users receive only meaningful, non-repetitive alerts.

The design follows a stateful filtering pattern where each guidance is evaluated against multiple criteria before being approved for announcement. The component maintains internal state for cooldown timers and distance tracking while remaining completely independent of UI and TTS concerns.

## Architecture

### Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    NavigationEngine                          │
│  (Converts YOLO detections → Guidance objects)              │
└────────────────────┬────────────────────────────────────────┘
                     │
                     │ Guidance + Detection metadata
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                  WarningRateLimiter                          │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  shouldAnnounce(guidance, width, xCenter)            │  │
│  │                                                       │  │
│  │  1. Global Cooldown Check (2.5s)                     │  │
│  │  2. Hard Suppression Rules                           │  │
│  │     - FAR distance                                   │  │
│  │     - MEDIUM (unless center + high priority)         │  │
│  │     - Small objects (width < 0.08)                   │  │
│  │     - Edge objects (x < 0.05 or x > 0.95)           │  │
│  │  3. Per-Object Cooldown (5s)                         │  │
│  │  4. Directional Cooldown (3s)                        │  │
│  │  5. Movement Sensitivity (distance change)           │  │
│  │                                                       │  │
│  │  → Returns: Boolean (allow/suppress)                 │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  recordAnnouncement(guidance)                        │  │
│  │                                                       │  │
│  │  - Update lastGlobalAlertTime                        │  │
│  │  - Update lastObjectAlertTime[label]                 │  │
│  │  - Update lastDirectionalAlertTime[label-direction]  │  │
│  │  - Update lastDistanceCategory[label]                │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  State:                                                      │
│  - lastGlobalAlertTime: Long                                │
│  - lastObjectAlertTime: Map<String, Long>                   │
│  - lastDirectionalAlertTime: Map<String, Long>              │
│  - lastDistanceCategory: Map<String, DistanceCategory>      │
└────────────────────┬────────────────────────────────────────┘
                     │
                     │ Boolean (approved/suppressed)
                     ▼
┌─────────────────────────────────────────────────────────────┐
│              TTS/UI Layer (Developer B)                      │
│  (Converts approved Guidance → speech/haptics)              │
└─────────────────────────────────────────────────────────────┘
```

### Integration with NavigationEngine

The NavigationEngine's `analyzeDetections()` method integrates the rate limiter as follows:

```kotlin
fun analyzeDetections(
    detections: List<NavigationDetection>,
    enableRateLimiting: Boolean = true
): Guidance? {
    // 1. Filter and rank detections
    val topGuidance = /* ... priority calculation ... */
    
    // 2. Apply rate limiting
    if (enableRateLimiting) {
        if (!WarningRateLimiter.shouldAnnounce(
            guidance = topGuidance,
            currentWidth = detection.width,
            currentXCenter = detection.xCenter
        )) {
            return null  // Suppressed
        }
        
        WarningRateLimiter.recordAnnouncement(topGuidance)
    }
    
    return topGuidance
}
```

## Components and Interfaces

### WarningRateLimiter Object

**Type:** Kotlin singleton object (thread-safe)

**Public API:**

```kotlin
object WarningRateLimiter {
    /**
     * Determines if a guidance should be announced.
     * 
     * @param guidance The guidance to evaluate
     * @param currentWidth Normalized width (0.0-1.0) for edge detection
     * @param currentXCenter Normalized xCenter (0.0-1.0) for edge detection
     * @return true if announcement allowed, false if suppressed
     */
    @Synchronized
    fun shouldAnnounce(
        guidance: Guidance,
        currentWidth: Float = 0.1f,
        currentXCenter: Float = 0.5f
    ): Boolean

    /**
     * Records that an announcement was made.
     * Updates all cooldown timers and distance tracking.
     * 
     * @param guidance The guidance that was announced
     */
    @Synchronized
    fun recordAnnouncement(guidance: Guidance)

    /**
     * Resets all cooldown timers and tracking state.
     * Useful for testing or starting a new navigation session.
     */
    @Synchronized
    fun reset()

    /**
     * Gets remaining cooldown time for a specific object label.
     * 
     * @param label The object label to check
     * @return Remaining cooldown in milliseconds, or 0 if ready
     */
    @Synchronized
    fun getRemainingCooldown(label: String): Long

    /**
     * Gets debug information about current cooldown state.
     * 
     * @return Formatted string with cooldown state
     */
    @Synchronized
    fun getDebugInfo(): String
}
```

### Configuration Constants

```kotlin
private const val GLOBAL_COOLDOWN_MS = 2500L      // 2.5 seconds
private const val PER_OBJECT_COOLDOWN_MS = 5000L  // 5 seconds
private const val DIRECTIONAL_COOLDOWN_MS = 3000L // 3 seconds
private const val MIN_WIDTH_THRESHOLD = 0.08f     // Minimum object width
private const val EDGE_THRESHOLD = 0.05f          // Edge detection threshold
private const val EDGE_MAX_THRESHOLD = 0.95f      // Edge detection max
```

## Data Models

### Internal State

```kotlin
// Timestamp of last announcement (any object)
private var lastGlobalAlertTime: Long = 0L

// Map: object label → timestamp of last announcement
private val lastObjectAlertTime = mutableMapOf<String, Long>()

// Map: "label-direction" → timestamp of last announcement
private val lastDirectionalAlertTime = mutableMapOf<String, Long>()

// Map: object label → last announced distance category
private val lastDistanceCategory = mutableMapOf<String, DistanceCategory>()
```

### Input Data

```kotlin
// From NavigationEngine
data class Guidance(
    val label: String,              // e.g., "person"
    val direction: Direction,       // LEFT/CENTER/RIGHT
    val distance: DistanceCategory, // VERY_CLOSE/CLOSE/MEDIUM/FAR
    val priority: Float             // Internal score
)

// Additional metadata from detection
currentWidth: Float    // Normalized 0.0-1.0
currentXCenter: Float  // Normalized 0.0-1.0
```

## Decision Logic Flow

### shouldAnnounce() Algorithm

```
Input: Guidance, currentWidth, currentXCenter
Output: Boolean (allow/suppress)

1. Check Global Cooldown
   IF (currentTime - lastGlobalAlertTime < 2500ms)
      RETURN false

2. Check Hard Suppression - Distance
   IF (distance == FAR)
      RETURN false
   
   IF (distance == MEDIUM)
      IF NOT (direction == CENTER AND priority > 10.0)
         RETURN false

3. Check Hard Suppression - Size
   IF (currentWidth < 0.08)
      RETURN false

4. Check Hard Suppression - Edge Position
   IF (currentXCenter < 0.05 OR currentXCenter > 0.95)
      RETURN false

5. Check Per-Object Cooldown
   lastTime = lastObjectAlertTime[label]
   IF (currentTime - lastTime < 5000ms)
      RETURN false

6. Check Directional Cooldown
   key = "${label}-${direction}"
   lastTime = lastDirectionalAlertTime[key]
   IF (currentTime - lastTime < 3000ms)
      RETURN false

7. Check Movement Sensitivity
   lastDist = lastDistanceCategory[label]
   IF (lastDist exists)
      IF NOT (distance is more dangerous than lastDist)
         RETURN false

8. All checks passed
   RETURN true
```

### Movement Sensitivity Logic

An object is considered "more dangerous" if its distance category transitions to a closer state:

```
FAR → MEDIUM, CLOSE, VERY_CLOSE  ✓ Allow
MEDIUM → CLOSE, VERY_CLOSE       ✓ Allow
CLOSE → VERY_CLOSE               ✓ Allow
VERY_CLOSE → VERY_CLOSE          ✗ Suppress
CLOSE → CLOSE                    ✗ Suppress
MEDIUM → MEDIUM                  ✗ Suppress
CLOSE → MEDIUM                   ✗ Suppress (moving away)
```

### recordAnnouncement() Algorithm

```
Input: Guidance

1. Get current timestamp
   currentTime = System.currentTimeMillis()

2. Update global cooldown
   lastGlobalAlertTime = currentTime

3. Update per-object cooldown
   lastObjectAlertTime[label] = currentTime

4. Update directional cooldown
   key = "${label}-${direction}"
   lastDirectionalAlertTime[key] = currentTime

5. Update distance tracking
   lastDistanceCategory[label] = distance
```

## Error Handling

### Thread Safety

All public methods are marked `@Synchronized` to ensure thread-safe access to mutable state. This prevents race conditions when multiple detection threads attempt to check or record announcements simultaneously.

### Edge Cases

1. **First announcement for an object**: When `lastObjectAlertTime[label]` doesn't exist, the cooldown check returns 0, allowing the announcement
2. **Null/missing metadata**: Default values (`currentWidth = 0.1f`, `currentXCenter = 0.5f`) prevent edge suppression for objects without metadata
3. **Clock rollback**: Uses `System.currentTimeMillis()` which is monotonic on modern Android devices
4. **Memory growth**: Maps grow with unique labels/directions, but in practice are bounded by YOLO's ~80 object classes

### Failure Modes

- **No failures expected**: All operations are in-memory and deterministic
- **Worst case**: If state becomes corrupted, `reset()` can be called to clear all timers

## Testing Strategy

### Unit Tests

The `WarningRateLimiterTest.kt` file should cover:

1. **Global Cooldown Test**
   - Announce object A
   - Immediately try to announce object B
   - Verify: B is suppressed
   - Wait 2.5 seconds
   - Try to announce object B again
   - Verify: B is allowed

2. **Per-Object Cooldown Test**
   - Announce "person"
   - Wait 2.6 seconds (global cooldown expires)
   - Try to announce "person" again
   - Verify: Suppressed (per-object cooldown still active)
   - Wait additional 2.5 seconds (total 5.1s)
   - Try to announce "person" again
   - Verify: Allowed

3. **Directional Cooldown Test**
   - Announce "person LEFT"
   - Wait 2.6 seconds
   - Try to announce "person RIGHT"
   - Verify: Allowed (different direction)
   - Try to announce "person LEFT"
   - Verify: Suppressed (directional cooldown)

4. **Movement Sensitivity Test**
   - Announce "chair" at MEDIUM distance
   - Record announcement
   - Try to announce "chair" at MEDIUM distance again
   - Verify: Suppressed (no distance change)
   - Try to announce "chair" at CLOSE distance
   - Verify: Allowed (became more dangerous)

5. **Hard Suppression Tests**
   - FAR distance → Verify: Suppressed
   - MEDIUM distance, LEFT direction, priority=5 → Verify: Suppressed
   - MEDIUM distance, CENTER direction, priority=15 → Verify: Allowed
   - Width=0.05 → Verify: Suppressed
   - xCenter=0.02 → Verify: Suppressed
   - xCenter=0.97 → Verify: Suppressed

6. **Reset Test**
   - Announce multiple objects
   - Call reset()
   - Verify: All cooldowns cleared
   - Verify: All objects can be announced immediately

7. **Debug Info Test**
   - Announce objects
   - Call getDebugInfo()
   - Verify: Output contains expected state information

### Integration Tests

Test with NavigationEngine:

```kotlin
fun testRateLimiterIntegration() {
    val engine = NavigationEngine()
    WarningRateLimiter.reset()
    
    // Create detection for "person" at center, close
    val detection = NavigationDetection(
        label = "person",
        confidence = 0.8f,
        xCenter = 0.5f,
        yCenter = 0.7f,
        width = 0.4f,
        height = 0.6f
    )
    
    // First call should return guidance
    val guidance1 = engine.analyzeDetections(listOf(detection))
    assert(guidance1 != null)
    
    // Immediate second call should return null (suppressed)
    val guidance2 = engine.analyzeDetections(listOf(detection))
    assert(guidance2 == null)
    
    // After 2.5 seconds, should still be suppressed (per-object cooldown)
    Thread.sleep(2600)
    val guidance3 = engine.analyzeDetections(listOf(detection))
    assert(guidance3 == null)
    
    // After 5 seconds total, should be allowed
    Thread.sleep(2500)
    val guidance4 = engine.analyzeDetections(listOf(detection))
    assert(guidance4 != null)
}
```

### Performance Tests

- **Latency**: `shouldAnnounce()` should complete in < 1ms
- **Memory**: State maps should not grow beyond ~100 entries in typical usage
- **Thread safety**: Concurrent calls should not cause race conditions

## Configuration and Tuning

### Adjustable Parameters

All cooldown durations and thresholds are defined as constants and can be tuned:

```kotlin
// Cooldown durations
GLOBAL_COOLDOWN_MS = 2500L      // Adjust for faster/slower announcement rate
PER_OBJECT_COOLDOWN_MS = 5000L  // Adjust for object-specific repetition
DIRECTIONAL_COOLDOWN_MS = 3000L // Adjust for directional repetition

// Suppression thresholds
MIN_WIDTH_THRESHOLD = 0.08f     // Lower = allow smaller objects
EDGE_THRESHOLD = 0.05f          // Lower = stricter edge filtering
```

### Tuning Guidelines

- **Increase GLOBAL_COOLDOWN_MS** if users report too many announcements overall
- **Decrease PER_OBJECT_COOLDOWN_MS** if users miss important object updates
- **Adjust MIN_WIDTH_THRESHOLD** based on camera field of view and typical object sizes
- **Adjust EDGE_THRESHOLD** based on detection accuracy at frame edges

## Dependencies

### Required Modules

- `Guidance.kt` - Data model for navigation guidance
- `Direction.kt` - Enum for LEFT/CENTER/RIGHT
- `DistanceCategory.kt` - Enum for VERY_CLOSE/CLOSE/MEDIUM/FAR

### No External Dependencies

- Does NOT depend on TTS libraries
- Does NOT depend on UI frameworks
- Does NOT depend on Android-specific APIs (except `System.currentTimeMillis()`)
- Pure Kotlin logic, easily testable

## Future Enhancements

### Potential Improvements

1. **Adaptive Cooldowns**: Adjust cooldown durations based on user walking speed or environment complexity
2. **Priority-Based Overrides**: Allow high-priority objects to bypass cooldowns in emergency situations
3. **Configurable Profiles**: Different cooldown settings for indoor vs outdoor navigation
4. **Analytics**: Track suppression rates to optimize thresholds
5. **Distance Velocity**: Consider rate of distance change, not just category transitions

### Not Planned

- TTS integration (belongs in UI layer)
- Haptic feedback (belongs in UI layer)
- Multi-object summarization (Step 3 feature)
- Scene context awareness (future NavigationEngine enhancement)
