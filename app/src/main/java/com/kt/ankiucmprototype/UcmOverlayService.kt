package com.kt.ankiucmprototype

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.ContextThemeWrapper // Add this import
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kt.ankiucmprototype.Constants.ACTION_RUN_OCR
import com.kt.ankiucmprototype.Constants.ACTION_START_CAPTURE
import com.kt.ankiucmprototype.Constants.ACTION_START_SNIPPING
import com.kt.ankiucmprototype.Constants.EXTRA_DATA
import com.kt.ankiucmprototype.Constants.EXTRA_RESULT_CODE
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs


/**
 * Core Foreground Service that takes care of the floating UI, screen capture with MediaProjection,
 * and OCR processing.
 *
 * This service is in charge of the accessibility overlay, which makes it always available.
 * A floating bubble for users to interact with and a background screen capture that runs in the
 * background and a text recognition pipeline that uses ML Kit.
 */
@Suppress("DEPRECATION")
@SuppressLint("AccessibilityPolicy")
class UcmOverlayService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var selectionOverlay: FrameLayout? = null
    private var snippingOverlay: SnippingOverlayView? = null

    private var isMenuExpanded = false

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var resultCode: Int = Activity.RESULT_CANCELED
    private var projectionData: Intent? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    companion object {
        private const val TAG = "UcmOverlayService"
        private const val TOUCH_SLOP = 10
        private const val NOTIFICATION_ID = 1234
        private const val CHANNEL_ID = "UCM_SERVICE_CHANNEL"

        var isServiceRunning = false
        var hasProjection = false
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        cleanupCache()
    }

    private fun createNotificationChannel() {
        val name = getString(R.string.ucm_service_name)
        val descriptionText = getString(R.string.ucm_service_description)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startForegroundServiceWithNotification(isCapturing: Boolean) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ucm_service_running))
            .setContentText(if (isCapturing) getString(R.string.ucm_capture_running) else getString(R.string.ucm_ready_to_capture))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val type = if (isCapturing) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        startForeground(NOTIFICATION_ID, notification, type)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                projectionData = intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)

                if (resultCode == Activity.RESULT_OK && projectionData != null) {
                    // Clean up any existing projection before starting a new one
                    stopCapture()
                    mediaProjection?.stop()

                    // Now that we have the intent, we can safely start foreground with mediaProjection type
                    startForegroundServiceWithNotification(isCapturing = true)
                    val projection = mediaProjectionManager.getMediaProjection(resultCode, projectionData!!)
                    
                    if (projection != null) {
                        // Register a callback as required by Android 14+ (API 34)
                        projection.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() {
                                super.onStop()
                                stopCapture()
                                mediaProjection = null
                                hasProjection = false
                                Log.d(TAG, "MediaProjection stopped")
                            }
                        }, Handler(Looper.getMainLooper()))
                        
                        mediaProjection = projection
                        hasProjection = true
                        Log.d(TAG, "MediaProjection created successfully")
                    } else {
                        Log.e(TAG, "Failed to get MediaProjection")
                    }
                }
            }
            ACTION_RUN_OCR -> {
                if (mediaProjection != null) {
                    captureScreenAndRunOCR()
                }
            }
            ACTION_START_SNIPPING -> {
                if (mediaProjection != null) {
                    startSnippingTool()
                }
            }
            else -> {
                // Start with specialUse if we don't have capture data yet
                startForegroundServiceWithNotification(isCapturing = false)
            }
        }
        return START_NOT_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubbleView()
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun addBubbleView() {
        // 1. Wrap the service context with a Material Components theme
        val contextThemeWrapper = ContextThemeWrapper(this, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar)

        // 2. Use the themed inflater
        val inflater = LayoutInflater.from(contextThemeWrapper)
        bubbleView = inflater.inflate(R.layout.bubble_layout, null)

        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        val mainFab = bubbleView?.findViewById<FloatingActionButton>(R.id.main_fab)
        val subButtonsContainer = bubbleView?.findViewById<LinearLayout>(R.id.sub_buttons_container)
        val btnTextMode = bubbleView?.findViewById<FloatingActionButton>(R.id.btn_text_mode)
        val btnImageMode = bubbleView?.findViewById<FloatingActionButton>(R.id.btn_image_mode)

        mainFab?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) {
                            isMoving = true
                        }

                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(bubbleView, layoutParams)
                        } catch (e: IllegalArgumentException) {
                            Log.e(TAG, "View not attached to window manager\n$e")
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoving) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })

        mainFab?.setOnClickListener {
            isMenuExpanded = !isMenuExpanded
            subButtonsContainer?.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE
            mainFab.setImageResource(if (isMenuExpanded) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_add)
        }

        btnTextMode?.setOnClickListener {
            toggleMenu()
            if (mediaProjection == null) {
                launchSetup()
            } else {
                captureScreenAndRunOCR()
            }
        }

        btnImageMode?.setOnClickListener {
            toggleMenu()
            if (mediaProjection == null) {
                launchSetup()
            } else {
                startSnippingTool()
            }
        }

        windowManager.addView(bubbleView, layoutParams)
    }

    private fun toggleMenu() {
        isMenuExpanded = false
        bubbleView?.findViewById<LinearLayout>(R.id.sub_buttons_container)?.visibility = View.GONE
        bubbleView?.findViewById<FloatingActionButton>(R.id.main_fab)?.setImageResource(android.R.drawable.ic_menu_add)
    }

    private fun launchSetup() {
        Log.d(TAG, "MediaProjection not ready, launching setup")
        val intent = Intent(this, CaptureSetupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun startSnippingTool() {
        Log.d(TAG, "Starting Snipping Tool")
        
        // Hide bubble while snipping
        bubbleView?.visibility = View.GONE

        snippingOverlay = SnippingOverlayView(this).apply {
            onSelectionConfirmed = { rect ->
                Log.d(TAG, "Selection confirmed: $rect")
                captureRegion(rect)
                removeSnippingOverlay()
            }
            // Add a way to cancel if needed, for now just removing it on confirm
        }

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(snippingOverlay, params)
    }

    private fun removeSnippingOverlay() {
        snippingOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing snipping overlay: ${e.message}")
            }
            snippingOverlay = null
        }
        bubbleView?.visibility = View.VISIBLE
    }

    private fun captureRegion(rect: Rect) {
        // For now, we reuse the existing OCR pipeline but could be extended for Image + Text
        // We'll pass the rect to the capture process
        captureScreenAndRunOCR(rect)
    }

    /**
     * Triggers the screen capture process and orchestrates OCR analysis.
     */
    private fun captureScreenAndRunOCR(region: Rect? = null) {
        val projection = mediaProjection
        if (projection == null) {
            Log.e(TAG, "MediaProjection not initialized")
            return
        }

        // Hide bubble before capturing to prevent it from appearing in OCR
        bubbleView?.visibility = View.GONE

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val screenDensity = metrics.densityDpi

        // Android 14+ requirement: Reuse the VirtualDisplay instead of recreating it
        if (virtualDisplay == null || imageReader == null) {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            try {
                virtualDisplay = projection.createVirtualDisplay(
                    "UCM_CAPTURE",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader!!.surface,
                    null,
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error creating virtual display: ${e.message}")
                return
            }
        } else if (imageReader?.width != screenWidth || imageReader?.height != screenHeight) {
            // Handle screen rotation or resize
            imageReader?.close()
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)
            virtualDisplay?.surface = imageReader?.surface
        }

        // Use a one-shot listener to get the next available frame
        Log.d(TAG, "Setting OnImageAvailableListener")
        imageReader?.setOnImageAvailableListener({ reader ->
            Log.d(TAG, "onImageAvailable triggered")
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring image: ${e.message}")
                null
            }
            
            if (image != null) {
                Log.d(TAG, "Image acquired, processing...")
                reader.setOnImageAvailableListener(null, null)
                processCapturedImage(image, screenWidth, screenHeight, region)
            } else {
                Log.d(TAG, "Image acquired was null")
            }
        }, Handler(Looper.getMainLooper()))

        // If an image is already available but the listener was set late, nudge the reader
        val existingImage = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null }
        if (existingImage != null) {
            Log.d(TAG, "Found existing image in buffer, processing immediately")
            imageReader?.setOnImageAvailableListener(null, null)
            processCapturedImage(existingImage, screenWidth, screenHeight, region)
        }
    }

    private fun processCapturedImage(image: android.media.Image, screenWidth: Int, screenHeight: Int, region: Rect? = null) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = createBitmap(screenWidth + rowPadding / pixelStride, screenHeight)
        bitmap.copyPixelsFromBuffer(buffer)

        // Full bitmap without row padding
        val fullBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

        var snipDimensions = ""
        // If a specific region was selected, save it to cache
        region?.let {
            try {
                // Ensure rect is within bitmap bounds
                val left = it.left.coerceIn(0, screenWidth - 1)
                val top = it.top.coerceIn(0, screenHeight - 1)
                val width = it.width().coerceIn(1, screenWidth - left)
                val height = it.height().coerceIn(1, screenHeight - top)
                
                val snipBitmap = Bitmap.createBitmap(fullBitmap, left, top, width, height)
                saveBitmapToCache(snipBitmap)
                snipDimensions = "${width}x${height}"
            } catch (e: Exception) {
                Log.e(TAG, "Error cropping to region: ${e.message}")
            }
        }

        runOCR(fullBitmap, region, snipDimensions)
        image.close()
    }

    private fun saveBitmapToCache(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        val file = File(cacheDir, "clip_$timestamp.png")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "Saved snip to ${file.absolutePath}")
            val cacheSize = cacheDir.listFiles()?.count { it.name.startsWith("clip_") } ?: 0
            Log.d("UCM_STORAGE", "There are $cacheSize files in cache.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snip to cache", e)
        }
    }

    // Clean up cache by deleting clips older than 10 minutes
    private fun cleanupCache() {
        val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("clip_") && file.lastModified() < tenMinutesAgo) {
                if (file.delete()) {
                    Log.d(TAG, "Deleted old cache file: ${file.name}")
                }
            }
        }
    }

    private fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    private fun runOCR(bitmap: Bitmap, snipRect: Rect? = null, snipDimensions: String = "") {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                val filteredBlocks = if (snipRect != null) {
                    result.textBlocks.filter { block ->
                        val blockRect = block.boundingBox
                        // Ignore block if its boundingBox is INSIDE the snipRect
                        blockRect == null || !snipRect.contains(blockRect)
                    }
                } else {
                    result.textBlocks
                }

                if (snipRect != null) {
                    Log.d(TAG, "Captured Image: $snipDimensions, Found ${filteredBlocks.size} text blocks outside snip")
                }

                showSelectionOverlay(filteredBlocks)
                bubbleView?.visibility = View.VISIBLE
            }
            .addOnFailureListener { e ->
                Log.e("UCM_OCR", "OCR Failed", e)
                bubbleView?.visibility = View.VISIBLE
            }
    }

    /**
     * Renders a selection overlay with detected text regions.
     *
     * @param textBlocks The list of [Text.TextBlock] objects
     */
    private fun showSelectionOverlay(textBlocks: List<Text.TextBlock>) {
        // Remove existing overlay if any
        selectionOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing the existing overlay: ${e.message}")
            }
        }

        val canvas = FrameLayout(this).apply {
            // Dim the background slightly
            setBackgroundColor(ContextCompat.getColor(this@UcmOverlayService, R.color.overlay_background))
            fitsSystemWindows = false
        }
        selectionOverlay = canvas

        // Add a Close button
        val closeButton = Button(this).apply {
            text = context.getString(R.string.close_overlay)
            layoutParams = FrameLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 100
                rightMargin = 50
            }
            setOnClickListener {
                dismissOverlay()
            }
        }
        canvas.addView(closeButton)

        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                isFitInsetsIgnoringVisibility = true
            }
        }

        val offsetX = 0
        val offsetY = 0

        for (block in textBlocks) {
            val rect = block.boundingBox ?: continue
            val textView = TextView(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@UcmOverlayService, R.color.text_highlight))
                val lp = FrameLayout.LayoutParams(rect.width(), rect.height())
                lp.gravity = Gravity.TOP or Gravity.START
                lp.leftMargin = rect.left + offsetX
                lp.topMargin = rect.top + offsetY
                layoutParams = lp
                
                setOnClickListener {
                    Log.d(TAG, "Selected text: ${block.text}")
                }
            }
            canvas.addView(textView)
        }

        windowManager.addView(canvas, params)
    }

    private fun dismissOverlay() {
        selectionOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay: ${e.message}")
            }
            selectionOverlay = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        hasProjection = false
        stopCapture()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing the bubble view: ${e.message}")
            }
        }
        selectionOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing the selection overlay: ${e.message}")
            }
        }
    }
}