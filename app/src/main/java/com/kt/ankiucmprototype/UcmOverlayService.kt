package com.kt.ankiucmprototype

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import kotlin.math.abs

@SuppressLint("AccessibilityPolicy")
class UcmOverlayService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    companion object {
        private const val TAG = "UcmOverlayService"
        private const val TOUCH_SLOP = 10
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
            Log.d(TAG, "Bubble clicked - starting screen traversal")
            traverseAndLogHierarchy()
        }

        windowManager.addView(bubbleView, layoutParams)
    }

    private fun traverseAndLogHierarchy() {
        val rootNode = rootInActiveWindow ?: return
        traverseNode(rootNode)
        rootNode.recycle()
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
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing view: ${e.message}")
            }
        }
    }
}