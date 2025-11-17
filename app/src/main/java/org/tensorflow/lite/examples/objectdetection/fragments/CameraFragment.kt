/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tensorflow.lite.examples.objectdetection.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import org.tensorflow.lite.examples.objectdetection.DepthEstimator
import org.tensorflow.lite.examples.objectdetection.NavigationGuidanceManager
import org.tensorflow.lite.examples.objectdetection.SceneAnalyzer
import org.tensorflow.lite.examples.objectdetection.navigation.PartitionNavGuidance
import org.tensorflow.lite.examples.objectdetection.navigation.ClosestObjectSpeaker
import org.tensorflow.lite.examples.objectdetection.navigation.DetectionConverter
import org.tensorflow.lite.examples.objectdetection.navigation.DepthEnricher
import org.tensorflow.lite.examples.objectdetection.navigation.SpeechCoordinator
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.tensorflow.lite.examples.objectdetection.ObjectDetectorHelper
import org.tensorflow.lite.examples.objectdetection.R
import org.tensorflow.lite.examples.objectdetection.databinding.FragmentCameraBinding
import org.tensorflow.lite.task.vision.detector.Detection

class CameraFragment : Fragment(), 
    ObjectDetectorHelper.DetectorListener,
    DepthEstimator.DepthEstimatorListener {

    private val TAG = "ObjectDetection"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private lateinit var sceneAnalyzer: SceneAnalyzer
    private lateinit var navigationGuidanceManager: NavigationGuidanceManager
    private lateinit var depthEstimator: DepthEstimator
    private lateinit var closestObjectSpeaker: ClosestObjectSpeaker
    private lateinit var partitionNavigation: PartitionNavGuidance
    private lateinit var speechCoordinator: SpeechCoordinator
    private lateinit var bitmapBuffer: Bitmap
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService
    
    /** Separate executor for depth estimation to avoid blocking object detection */
    private lateinit var depthExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(CameraFragmentDirections.actionCameraToPermissions())
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()
        
        // Shutdown depth estimator
        if (::depthEstimator.isInitialized) {
            depthEstimator.close()
        }
        
        // Shutdown depth executor
        if (::depthExecutor.isInitialized) {
            depthExecutor.shutdown()
        }
        
        // Shutdown scene analyzer (includes Gemini client and TTS)
        if (::sceneAnalyzer.isInitialized) {
            sceneAnalyzer.shutdown()
        }
        
        // Shutdown navigation guidance manager
        if (::navigationGuidanceManager.isInitialized) {
            navigationGuidanceManager.shutdown()
        }
        
        // Shutdown closest object speaker
        if (::closestObjectSpeaker.isInitialized) {
            closestObjectSpeaker.shutdown()
        }
        
        // Shutdown speech coordinator
        if (::speechCoordinator.isInitialized) {
            speechCoordinator.shutdown()
        }
    }

    override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize object detector
        objectDetectorHelper = ObjectDetectorHelper(
            context = requireContext(),
            objectDetectorListener = this)

        // Initialize Scene Analyzer with Gemini API
        // API key hardcoded directly
        sceneAnalyzer = SceneAnalyzer(
            context = requireContext(),
            apiKey = "AIzaSyBImwWWocwj5BfPswuMZtTsYj6P11ymB7o"
        )
        
        // Initialize MiDaS Depth Estimator first (needed by NavigationGuidanceManager)
        depthEstimator = DepthEstimator(
            context = requireContext(),
            listener = this
        )
        depthEstimator.initialize()
        Log.i(TAG, "✓ MiDaS depth estimator initialized")
        
        // Initialize Navigation Guidance Manager with depth estimator for accurate distance
        navigationGuidanceManager = NavigationGuidanceManager(
            context = requireContext(),
            depthEstimator = depthEstimator
        )
        
        // Initialize Speech Coordinator for managing TTS priorities
        speechCoordinator = SpeechCoordinator(requireContext())
        
        // Initialize Partition-Based Navigation Guidance (replaces angle-based)
        partitionNavigation = PartitionNavGuidance(
            context = requireContext(),
            speechCoordinator = speechCoordinator
        )
        
        // Initialize Closest Object Speaker for single-object announcements
        closestObjectSpeaker = ClosestObjectSpeaker(requireContext())
        closestObjectSpeaker.setSpeechCoordinator(speechCoordinator)

        // Initialize our background executors
        cameraExecutor = Executors.newSingleThreadExecutor()
        depthExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        // Attach listeners to UI control widgets
        initBottomSheetControls()
        
        // Setup Analyze Surroundings button
        setupAnalyzeSurroundingsButton()
    }

    private fun initBottomSheetControls() {
        // When clicked, lower detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.thresholdMinus.setOnClickListener {
            if (objectDetectorHelper.threshold >= 0.1) {
                objectDetectorHelper.threshold -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.thresholdPlus.setOnClickListener {
            if (objectDetectorHelper.threshold <= 0.8) {
                objectDetectorHelper.threshold += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, reduce the number of objects that can be detected at a time
        fragmentCameraBinding.bottomSheetLayout.maxResultsMinus.setOnClickListener {
            if (objectDetectorHelper.maxResults > 1) {
                objectDetectorHelper.maxResults--
                updateControlsUi()
            }
        }

        // When clicked, increase the number of objects that can be detected at a time
        fragmentCameraBinding.bottomSheetLayout.maxResultsPlus.setOnClickListener {
            if (objectDetectorHelper.maxResults < 5) {
                objectDetectorHelper.maxResults++
                updateControlsUi()
            }
        }

        // When clicked, decrease the number of threads used for detection
        fragmentCameraBinding.bottomSheetLayout.threadsMinus.setOnClickListener {
            if (objectDetectorHelper.numThreads > 1) {
                objectDetectorHelper.numThreads--
                updateControlsUi()
            }
        }

        // When clicked, increase the number of threads used for detection
        fragmentCameraBinding.bottomSheetLayout.threadsPlus.setOnClickListener {
            if (objectDetectorHelper.numThreads < 4) {
                objectDetectorHelper.numThreads++
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference. Current options are CPU
        // GPU, and NNAPI
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(0, false)
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                    objectDetectorHelper.currentDelegate = p2
                    updateControlsUi()
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }

        // When clicked, change the underlying model used for object detection
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.setSelection(0, false)
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                    objectDetectorHelper.currentModel = p2
                    updateControlsUi()
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    // Update the values displayed in the bottom sheet. Reset detector.
    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxResultsValue.text =
            objectDetectorHelper.maxResults.toString()
        fragmentCameraBinding.bottomSheetLayout.thresholdValue.text =
            String.format("%.2f", objectDetectorHelper.threshold)
        fragmentCameraBinding.bottomSheetLayout.threadsValue.text =
            objectDetectorHelper.numThreads.toString()

        // Needs to be cleared instead of reinitialized because the GPU
        // delegate needs to be initialized on the thread using it when applicable
        objectDetectorHelper.clearObjectDetector()
        fragmentCameraBinding.overlay.clear()
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }


    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector - using standard back camera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        detectObjects(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectObjects(image: ImageProxy) {
        // Recreate bitmap buffer if dimensions changed (can happen with ultrawide camera)
        if (!::bitmapBuffer.isInitialized || 
            bitmapBuffer.width != image.width || 
            bitmapBuffer.height != image.height) {
            
            if (::bitmapBuffer.isInitialized) {
                Log.d(TAG, "Image dimensions changed: ${bitmapBuffer.width}x${bitmapBuffer.height} -> ${image.width}x${image.height}")
            }
            
            bitmapBuffer = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            Log.d(TAG, "Created bitmap buffer: ${image.width}x${image.height}")
        }
        
        // Copy out RGB bits to the shared bitmap buffer
        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }

        val imageRotation = image.imageInfo.rotationDegrees
        
        // Run object detection (on camera executor thread)
        objectDetectorHelper.detect(bitmapBuffer, imageRotation)
        
        // Run depth estimation in parallel (on separate depth executor thread)
        depthExecutor.execute {
            depthEstimator.estimateDepth(bitmapBuffer)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after objects have been detected. Extracts original image height/width
    // to scale and place bounding boxes properly through OverlayView
    override fun onResults(
      results: MutableList<Detection>?,
      inferenceTime: Long,
      imageHeight: Int,
      imageWidth: Int
    ) {
        activity?.runOnUiThread {
            fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                            String.format("%d ms", inferenceTime)

            // Pass necessary information to OverlayView for drawing on the canvas
            fragmentCameraBinding.overlay.setResults(
                results ?: LinkedList<Detection>(),
                imageHeight,
                imageWidth
            )

            // Force a redraw
            fragmentCameraBinding.overlay.invalidate()
            
            // ===== CLOSEST OBJECT DETECTION (MIDAS DEPTH) =====
            // Speaks ONLY the single closest object using MiDaS depth estimation
            // - Filters by confidence >= 0.40
            // - Selects object with smallest distanceMeters
            // - EMA smoothing (alpha=0.35) to avoid jitter
            // - Hysteresis: Only speaks if distance changes >0.3m OR label changes
            // - Cooldown: Maximum once every 1200ms
            // - Format: "[label], about X.X meters to your left/right/ahead"
            if (results != null && results.isNotEmpty()) {
                // Convert to NavigationDetections
                val navigationDetections = results.mapNotNull { detection ->
                    try {
                        DetectionConverter.toNavigationDetection(detection, imageWidth, imageHeight)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to convert detection: ${e.message}")
                        null
                    }
                }
                
                // Enrich with MiDaS depth
                val enrichedDetections = DepthEnricher.enrichWithDepth(
                    navigationDetections,
                    depthEstimator,
                    imageWidth,
                    imageHeight
                )
                
                // ===== PARTITION-BASED NAVIGATION GUIDANCE =====
                // Provides complete navigation guidance with object identification
                // Format: "frisbee ahead, 2.5 meters, step right"
                // - Uses region occupancy and overlap instead of angles
                // - Includes object label, distance, and navigation instruction
                // - Single unified speech output (no separate ClosestObjectSpeaker needed)
                partitionNavigation.update(enrichedDetections, imageWidth, imageHeight)
                
                // ===== CLOSEST OBJECT SPEAKER - DISABLED =====
                // Now handled by PartitionNavGuidance which includes object label
                // closestObjectSpeaker.processDetections(enrichedDetections, imageWidth)
            }
            
            // ===== OLD SPEECH SYSTEMS - DISABLED =====
            // These are commented out to prevent multiple speech outputs
            // Only ClosestObjectSpeaker should speak now
            
            // DISABLED: PATH GUIDANCE (was speaking about all obstacles)
            // if (results != null && results.isNotEmpty()) {
            //     navigationGuidanceManager.providePathGuidance(results, imageWidth, imageHeight)
            // }
            
            // DISABLED: SCENE SUMMARY (was speaking multi-object summaries)
            // if (results != null && results.isNotEmpty()) {
            //     if (navigationGuidanceManager.shouldAutoSummarize(results, imageWidth, imageHeight)) {
            //         navigationGuidanceManager.speakSceneSummary(results, imageWidth, imageHeight)
            //     }
            // }
            
            // DISABLED: GEMINI VISION (was speaking scene analysis)
            // if (results != null && results.isNotEmpty() && ::bitmapBuffer.isInitialized) {
            //     sceneAnalyzer.analyzeScene(bitmapBuffer, results)
            // }
        }
    }

    // ObjectDetectorHelper.DetectorListener callback
    override fun onError(error: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Setup the Analyze Surroundings button
     * When clicked, sends current camera frame to Gemini for comprehensive analysis
     */
    private fun setupAnalyzeSurroundingsButton() {
        fragmentCameraBinding.analyzeSurroundingsButton.setOnClickListener {
            Log.d(TAG, "🔍 Analyze Surroundings button clicked")
            
            // Check if bitmap is initialized
            if (!::bitmapBuffer.isInitialized) {
                Toast.makeText(
                    requireContext(),
                    "Camera not ready. Please wait.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            
            // Get current detections (optional, for fallback)
            val currentDetections = fragmentCameraBinding.overlay.getDetections()
            
            // Show feedback to user
            Toast.makeText(
                requireContext(),
                "Analyzing surroundings...",
                Toast.LENGTH_SHORT
            ).show()
            
            // PAUSE navigation guidance to avoid speech overlap
            navigationGuidanceManager.pauseGuidance()
            Log.d(TAG, "⏸️ Navigation guidance paused for manual analysis")
            
            // Call manual analysis with completion callback
            sceneAnalyzer.analyzeSurroundingsManually(
                bitmap = bitmapBuffer,
                detections = currentDetections,
                onComplete = {
                    // Resume navigation guidance after analysis completes
                    activity?.runOnUiThread {
                        navigationGuidanceManager.resumeGuidance()
                        Log.d(TAG, "▶️ Navigation guidance resumed after manual analysis")
                    }
                }
            )
            
            Log.d(TAG, "✓ Manual analysis triggered")
        }
    }
    
    // ===== DEPTH ESTIMATOR LISTENER CALLBACKS =====
    
    override fun onDepthMapReady(
        depthMap: FloatArray,
        width: Int,
        height: Int,
        inferenceTime: Long
    ) {
        // Depth map is cached in DepthEstimator, no action needed here
        // Just log for monitoring
        Log.d(TAG, "Depth map ready: ${width}x${height}, inference time: ${inferenceTime}ms")
    }
    
    override fun onDepthEstimationError(error: String) {
        // Depth estimation error - log but don't crash
        // Navigation will fall back to pixel-based distance
        Log.w(TAG, "Depth estimation error: $error")
    }
}
