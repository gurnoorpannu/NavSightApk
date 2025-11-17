package org.tensorflow.lite.examples.objectdetection.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Coordinates speech output between navigation guidance and closest object speaker
 * Manages priorities, suppression, and interruption
 */
class SpeechCoordinator(context: Context) {
    
    companion object {
        private const val TAG = "SpeechCoordinator"
    }
    
    /**
     * Speech priority levels
     */
    enum class Priority {
        URGENT,      // STOP - interrupts everything
        NAVIGATION,  // Turn instructions
        INFORMATION  // ClosestObjectSpeaker
    }
    
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    
    // Suppression tracking
    private var closestObjectSuppressedUntil: Long = 0L
    
    init {
        initializeTextToSpeech(context)
    }
    
    private fun initializeTextToSpeech(context: Context) {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                
                if (isTtsReady) {
                    textToSpeech?.setSpeechRate(1.2f)
                    textToSpeech?.setPitch(1.0f)
                    Log.d(TAG, "SpeechCoordinator TTS initialized")
                }
            }
        }
    }
    
    /**
     * Request speech output
     * 
     * @param message Text to speak
     * @param priority Priority level
     * @param interruptActive If true, interrupt any ongoing speech
     * @return True if speech was approved and spoken
     */
    fun requestSpeech(
        message: String,
        priority: Priority,
        interruptActive: Boolean = false
    ): Boolean {
        if (!isTtsReady || textToSpeech == null) {
            Log.w(TAG, "TTS not ready, cannot speak: $message")
            return false
        }
        
        // Determine queue mode
        val queueMode = if (interruptActive) {
            TextToSpeech.QUEUE_FLUSH  // Interrupt ongoing speech
        } else {
            TextToSpeech.QUEUE_ADD  // Queue after current speech
        }
        
        // Speak
        textToSpeech?.speak(message, queueMode, null, "navigation_${priority.name}")
        
        Log.d(TAG, "✓ SPEAKING: \"$message\" [priority=$priority, interrupt=$interruptActive]")
        return true
    }
    
    /**
     * Suppress ClosestObjectSpeaker for specified duration
     * 
     * @param durationMs Duration in milliseconds
     */
    fun suppressClosestObjectSpeaker(durationMs: Long) {
        closestObjectSuppressedUntil = System.currentTimeMillis() + durationMs
        Log.d(TAG, "Suppressing ClosestObjectSpeaker for ${durationMs}ms")
    }
    
    /**
     * Check if ClosestObjectSpeaker is currently suppressed
     * 
     * @return True if suppressed
     */
    fun isClosestObjectSpeakerSuppressed(): Boolean {
        val now = System.currentTimeMillis()
        val suppressed = now < closestObjectSuppressedUntil
        
        if (suppressed) {
            val remaining = closestObjectSuppressedUntil - now
            Log.d(TAG, "ClosestObjectSpeaker suppressed (${remaining}ms remaining)")
        }
        
        return suppressed
    }
    
    /**
     * Stop any ongoing speech
     */
    fun stopSpeech() {
        textToSpeech?.stop()
        Log.d(TAG, "Speech stopped")
    }
    
    /**
     * Cleanup resources
     */
    fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        Log.d(TAG, "SpeechCoordinator shutdown")
    }
}
