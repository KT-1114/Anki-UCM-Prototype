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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import androidx.core.graphics.createBitmap

@Suppress("DEPRECATION")
@SuppressLint("AccessibilityPolicy")
class UcmOverlayService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

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
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val name = "UCM Service"
        val descriptionText = "Monitoring for screen capture"
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
            .setContentTitle("UCM is running")
            .setContentText(if (isCapturing) "Screen capture active" else "Ready to capture")
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
        if (intent?.action == "START_CAPTURE") {
            resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            projectionData =
                intent.getParcelableExtra("DATA", Intent::class.java)

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
                            Log.d(TAG, "MediaProjection stopped")
                        }
                    }, Handler(Looper.getMainLooper()))
                    
                    mediaProjection = projection
                    Log.d(TAG, "MediaProjection created successfully")
                } else {
                    Log.e(TAG, "Failed to get MediaProjection")
                }
            }
        } else {
            // Start with specialUse if we don't have capture data yet
            startForegroundServiceWithNotification(isCapturing = false)
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
        val inflater = LayoutInflater.from(this)
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

        val bubbleIcon = bubbleView?.findViewById<ImageView>(R.id.bubble_icon)

        bubbleIcon?.setOnTouchListener(object : View.OnTouchListener {
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

        bubbleIcon?.setOnClickListener {
            if (mediaProjection == null) {
                Log.d(TAG, "MediaProjection not ready, launching setup")
                val intent = Intent(this, CaptureSetupActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } else {
                Log.d(TAG, "Bubble clicked - starting OCR")
                captureScreenAndRunOCR()
            }
        }

        windowManager.addView(bubbleView, layoutParams)
    }

    private fun captureScreenAndRunOCR() {
        val projection = mediaProjection
        if (projection == null) {
            Log.e(TAG, "MediaProjection not initialized")
            return
        }

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
                processCapturedImage(image, screenWidth, screenHeight)
            } else {
                Log.d(TAG, "Image acquired was null")
            }
        }, Handler(Looper.getMainLooper()))

        // If an image is already available but the listener was set late, nudge the reader
        val existingImage = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null }
        if (existingImage != null) {
            Log.d(TAG, "Found existing image in buffer, processing immediately")
            imageReader?.setOnImageAvailableListener(null, null)
            processCapturedImage(existingImage, screenWidth, screenHeight)
        }
    }

    private fun processCapturedImage(image: android.media.Image, screenWidth: Int, screenHeight: Int) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = createBitmap(screenWidth + rowPadding / pixelStride, screenHeight)
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop the bitmap to remove padding
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)

        runOCR(croppedBitmap)
        image.close()
    }

    private fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    private fun runOCR(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                for (block in result.textBlocks) {
                    val blockText = block.text
                    val blockFrame = block.boundingBox
                    Log.d("UCM_OCR", "Block: $blockText, Bounds: $blockFrame")
                }
            }
            .addOnFailureListener { e ->
                Log.e("UCM_OCR", "OCR Failed", e)
            }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?) {
        if (node == null) return

        val text = node.text?.toString()
        if (!text.isNullOrBlank() && !node.isPassword) {
            Log.d(TAG, "Meaningful String: $text")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child)
                child.recycle()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing view: ${e.message}")
            }
        }
    }
}