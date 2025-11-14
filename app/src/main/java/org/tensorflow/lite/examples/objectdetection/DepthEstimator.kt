package org.tensorflow.lite.examples.objectdetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * MiDaS Depth Estimation Component
 * 
 * Provides accurate depth estimation using the MiDaS TFLite model.
 * Replaces the old pixel-based depth estimation with real depth inference.
 * 
 * Model Details:
 * - Input: Float32 [1, 256, 256, 3] RGB normalized to [0.0, 1.0]
 * - Output: Float32 [1, 256, 256, 1] inverse relative depth
 * - Location: assets/midas.tflite
 */
class DepthEstimator(
    private val context: Context,
    private val listener: DepthEstimatorListener? = null
) {
    
    companion object {
        private const val TAG = "DepthEstimator"
    }
    
    interface DepthEstimatorListener {
        fun onDepthMapReady(depthMap: FloatArray, width: Int, height: Int, inferenceTime: Long)
        fun onDepthEstimationError(error: String)
    }
    
    // TensorFlow Lite components
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // Reusable buffers for performance
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    
    // Cached depth map with thread-safe access
    private var cachedDepthMap: DepthMap? = null
    private val depthMapLock = ReentrantReadWriteLock()
    
    // Initialization state
    @Volatile
    private var isInitialized = false
    
    /**
     * Data class for cached depth map
     */
    data class DepthMap(
        val data: FloatArray,
        val width: Int,
        val height: Int,
        val timestamp: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as DepthMap
            
            if (!data.contentEquals(other.data)) return false
            if (width != other.width) return false
            if (height != other.height) return false
            if (timestamp != other.timestamp) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
    
    /**
     * Initialize the MiDaS model and interpreter
     * Should be called once before using the estimator
     */
    fun initialize() {
        try {
            Log.i(TAG, "Initializing MiDaS depth estimator...")
            
            // Load model from assets
            val modelBuffer = loadModelFile()
            
            // Configure interpreter options
            val options = Interpreter.Options()
            
            // Try GPU acceleration first
            val compatibilityList = CompatibilityList()
            if (compatibilityList.isDelegateSupportedOnThisDevice) {
                try {
                    gpuDelegate = GpuDelegate(compatibilityList.bestOptionsForThisDevice)
                    options.addDelegate(gpuDelegate)
                    Log.i(TAG, "GPU delegate enabled for depth estimation")
                } catch (e: Exception) {
                    Log.w(TAG, "GPU delegate failed, trying NNAPI: ${e.message}")
                    gpuDelegate?.close()
                    gpuDelegate = null
                    
                    // Fallback to NNAPI
                    try {
                        options.setUseNNAPI(true)
                        Log.i(TAG, "NNAPI enabled for depth estimation")
                    } catch (e2: Exception) {
                        Log.w(TAG, "NNAPI failed, using CPU: ${e2.message}")
                    }
                }
            } else {
                Log.i(TAG, "GPU not supported, using CPU for depth estimation")
            }
            
            // Set number of threads for CPU inference
            options.setNumThreads(DepthConfig.NUM_THREADS)
            
            // Create interpreter
            interpreter = Interpreter(modelBuffer, options)
            
            // Allocate reusable buffers
            allocateBuffers()
            
            isInitialized = true
            Log.i(TAG, "MiDaS depth estimator initialized successfully")
            
        } catch (e: Exception) {
            val error = "Failed to initialize MiDaS model: ${e.message}"
            Log.e(TAG, error, e)
            listener?.onDepthEstimationError(error)
            isInitialized = false
        }
    }
    
    /**
     * Load the MiDaS model file from assets
     */
    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(DepthConfig.MODEL_PATH)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    /**
     * Allocate reusable input and output buffers
     */
    private fun allocateBuffers() {
        // Input: [1, 256, 256, 3] Float32 = 1 * 256 * 256 * 3 * 4 bytes
        val inputSize = 1 * DepthConfig.INPUT_SIZE * DepthConfig.INPUT_SIZE * DepthConfig.CHANNELS * 4
        inputBuffer = ByteBuffer.allocateDirect(inputSize).apply {
            order(ByteOrder.nativeOrder())
        }
        
        // Output: [1, 256, 256, 1] Float32 = 1 * 256 * 256 * 1 * 4 bytes
        val outputSize = 1 * DepthConfig.INPUT_SIZE * DepthConfig.INPUT_SIZE * 1 * 4
        outputBuffer = ByteBuffer.allocateDirect(outputSize).apply {
            order(ByteOrder.nativeOrder())
        }
        
        Log.d(TAG, "Allocated buffers: input=$inputSize bytes, output=$outputSize bytes")
    }
    
    /**
     * Estimate depth from a bitmap
     * This method can be called from any thread
     */
    fun estimateDepth(bitmap: Bitmap) {
        if (!isInitialized) {
            Log.w(TAG, "DepthEstimator not initialized, skipping inference")
            return
        }
        
        try {
            val startTime = System.currentTimeMillis()
            
            // Preprocess bitmap to model input format
            val input = preprocessBitmap(bitmap)
            
            // Prepare output buffer
            val output = getOrCreateOutputBuffer()
            output.rewind()
            
            // Run inference
            interpreter?.run(input, output)
            
            // Extract depth map
            val depthMap = extractDepthMap(output)
            
            // Cache the depth map
            cacheDepthMap(depthMap)
            
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Depth inference completed in ${inferenceTime}ms")
            
            // Notify listener
            listener?.onDepthMapReady(depthMap, DepthConfig.INPUT_SIZE, DepthConfig.INPUT_SIZE, inferenceTime)
            
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Inference failed (invalid arguments): ${e.message}", e)
            listener?.onDepthEstimationError("Depth inference failed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during inference: ${e.message}", e)
            listener?.onDepthEstimationError("Depth estimation error: ${e.message}")
        }
    }
    
    /**
     * Preprocess bitmap to model input format
     * Resizes to 256x256 and normalizes to [0.0, 1.0]
     */
    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val buffer = getOrCreateInputBuffer()
        buffer.rewind()
        
        // Resize bitmap to 256x256
        val resized = Bitmap.createScaledBitmap(bitmap, DepthConfig.INPUT_SIZE, DepthConfig.INPUT_SIZE, true)
        
        // Extract pixels
        val pixels = IntArray(DepthConfig.INPUT_SIZE * DepthConfig.INPUT_SIZE)
        resized.getPixels(pixels, 0, DepthConfig.INPUT_SIZE, 0, 0, DepthConfig.INPUT_SIZE, DepthConfig.INPUT_SIZE)
        
        // Convert to normalized RGB float values
        for (pixel in pixels) {
            // Extract RGB channels and normalize to [0.0, 1.0]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }
        
        buffer.rewind()
        return buffer
    }
    
    /**
     * Extract depth map from output buffer
     */
    private fun extractDepthMap(outputBuffer: ByteBuffer): FloatArray {
        outputBuffer.rewind()
        val depthMap = FloatArray(DepthConfig.INPUT_SIZE * DepthConfig.INPUT_SIZE)
        
        for (i in depthMap.indices) {
            depthMap[i] = outputBuffer.float
        }
        
        return depthMap
    }
    
    /**
     * Cache depth map with thread-safe access
     */
    private fun cacheDepthMap(depthMap: FloatArray) {
        if (DepthConfig.CACHE_DEPTH_MAP) {
            depthMapLock.write {
                cachedDepthMap = DepthMap(
                    data = depthMap,
                    width = DepthConfig.INPUT_SIZE,
                    height = DepthConfig.INPUT_SIZE,
                    timestamp = System.currentTimeMillis()
                )
            }
        }
    }
    
    /**
     * Get the latest cached depth map (thread-safe)
     */
    fun getLatestDepthMap(): DepthMap? {
        return depthMapLock.read {
            cachedDepthMap
        }
    }
    
    /**
     * Get or create input buffer
     */
    private fun getOrCreateInputBuffer(): ByteBuffer {
        return inputBuffer ?: ByteBuffer.allocateDirect(
            1 * DepthConfig.INPUT_SIZE * DepthConfig.INPUT_SIZE * DepthConfig.CHANNELS * 4
        ).apply {
            order(ByteOrder.nativeOrder())
            inputBuffer = this
        }
    }
    
    /**
     * Get or create output buffer
     */
    private fun getOrCreateOutputBuffer(): ByteBuffer {
        return outputBuffer ?: ByteBuffer.allocateDirect(
            1 * DepthConfig.INPUT_SIZE * DepthConfig.INPUT_SIZE * 1 * 4
        ).apply {
            order(ByteOrder.nativeOrder())
            outputBuffer = this
        }
    }
    
    /**
     * Compute median depth for a bounding box region
     * 
     * @param boundingBox Bounding box in normalized coordinates [0.0, 1.0]
     * @param imageWidth Original image width
     * @param imageHeight Original image height
     * @return Median depth value, or null if depth map unavailable
     */
    fun getMedianDepthForRegion(
        boundingBox: RectF,
        imageWidth: Int,
        imageHeight: Int
    ): Float? {
        val depthMap = getLatestDepthMap() ?: return null
        
        // Map bounding box to depth map coordinates
        val left = (boundingBox.left * depthMap.width).toInt().coerceIn(0, depthMap.width - 1)
        val right = (boundingBox.right * depthMap.width).toInt().coerceIn(0, depthMap.width - 1)
        val top = (boundingBox.top * depthMap.height).toInt().coerceIn(0, depthMap.height - 1)
        val bottom = (boundingBox.bottom * depthMap.height).toInt().coerceIn(0, depthMap.height - 1)
        
        // Collect depth values in region
        val values = mutableListOf<Float>()
        for (y in top..bottom) {
            for (x in left..right) {
                val index = y * depthMap.width + x
                if (index < depthMap.data.size) {
                    values.add(depthMap.data[index])
                }
            }
        }
        
        if (values.isEmpty()) {
            return null
        }
        
        // Return median
        values.sort()
        return values[values.size / 2]
    }
    
    /**
     * Convert relative depth to approximate meters
     * MiDaS outputs relative depth values - higher values = farther away
     * We scale these to approximate meters
     */
    fun depthToMeters(relativeDepth: Float): Float {
        // MiDaS outputs relative depth (not inverse)
        // Scale factor converts relative depth to approximate meters
        // Typical MiDaS values range from ~100 (close) to ~1000 (far)
        val meters = (relativeDepth / DepthConfig.DEPTH_SCALE_FACTOR).coerceAtLeast(0.01f)
        val clampedMeters = meters.coerceIn(DepthConfig.MIN_DEPTH_METERS, DepthConfig.MAX_DEPTH_METERS)
        
        // Log calibration data if enabled
        if (DepthConfig.ENABLE_CALIBRATION_LOGGING) {
            DepthConfig.logCalibrationData("unknown", relativeDepth, clampedMeters)
        }
        
        return clampedMeters
    }
    
    /**
     * Clean up resources
     * Must be called when done using the estimator
     */
    fun close() {
        Log.i(TAG, "Closing DepthEstimator...")
        
        interpreter?.close()
        interpreter = null
        
        gpuDelegate?.close()
        gpuDelegate = null
        
        inputBuffer = null
        outputBuffer = null
        
        depthMapLock.write {
            cachedDepthMap = null
        }
        
        isInitialized = false
        Log.i(TAG, "DepthEstimator closed")
    }
}
