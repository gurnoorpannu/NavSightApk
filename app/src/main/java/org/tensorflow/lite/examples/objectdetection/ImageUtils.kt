package org.tensorflow.lite.examples.objectdetection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * ImageUtils - Utility functions for image conversion
 * 
 * Provides efficient conversion between:
 * - ImageProxy (CameraX) → Bitmap
 * - Bitmap → JPEG bytes
 * - YUV → RGB conversion
 */
object ImageUtils {
    
    /**
     * Converts CameraX ImageProxy to Bitmap
     * 
     * Handles different image formats:
     * - RGBA_8888 (direct copy)
     * - YUV_420_888 (conversion required)
     * 
     * @param imageProxy CameraX image
     * @return Bitmap or null if conversion fails
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            when (imageProxy.format) {
                ImageFormat.YUV_420_888 -> {
                    // Convert YUV to Bitmap
                    yuvToBitmap(imageProxy)
                }
                else -> {
                    // For RGBA_8888, direct copy from buffer
                    val buffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    
                    val bitmap = Bitmap.createBitmap(
                        imageProxy.width,
                        imageProxy.height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                    bitmap
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Error converting ImageProxy to Bitmap", e)
            null
        }
    }
    
    /**
     * Converts YUV_420_888 ImageProxy to Bitmap
     * 
     * @param imageProxy YUV format image
     * @return Bitmap
     */
    private fun yuvToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)
        
        // Copy UV planes (interleaved for NV21)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        // Convert NV21 to Bitmap
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()
        
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
    
    /**
     * Converts Bitmap to JPEG byte array
     * 
     * @param bitmap Input bitmap
     * @param quality JPEG quality (0-100)
     * @return JPEG bytes
     */
    fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }
    
    /**
     * Creates a copy of bitmap from ImageProxy buffer
     * Optimized for RGBA_8888 format used in object detection
     * 
     * @param imageProxy CameraX image
     * @param existingBitmap Reusable bitmap (optional)
     * @return Bitmap
     */
    fun copyBitmapFromImageProxy(imageProxy: ImageProxy, existingBitmap: Bitmap? = null): Bitmap {
        val bitmap = existingBitmap ?: Bitmap.createBitmap(
            imageProxy.width,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        
        // Copy pixels from buffer
        bitmap.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
        
        return bitmap
    }
}
