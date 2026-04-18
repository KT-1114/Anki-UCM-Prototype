package com.kt.ankiucmprototype

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.view.Gravity

class SnippingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDrawing = false
    
    var selectedRect: Rect? = null
        private set

    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
        alpha = 150 // Semi-transparent
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val strokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val confirmButton: ImageButton

    var onSelectionConfirmed: ((Rect) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)

        confirmButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.checkbox_on_background) // Standard checkmark
            visibility = GONE
            background = null
            setOnClickListener {
                selectedRect?.let { onSelectionConfirmed?.invoke(it) }
            }
        }
        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 100
        }
        addView(confirmButton, params)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = event.x
                currentY = event.y
                isDrawing = true
                confirmButton.visibility = GONE
                selectedRect = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDrawing = false
                updateSelectedRect()
                if (selectedRect != null && (selectedRect?.width() ?: 0) > 10 && (selectedRect?.height() ?: 0) > 10) {
                    confirmButton.visibility = VISIBLE
                } else {
                    selectedRect = null
                    confirmButton.visibility = GONE
                    performClick()
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateSelectedRect() {
        val left = minOf(startX, currentX).toInt()
        val top = minOf(startY, currentY).toInt()
        val right = maxOf(startX, currentX).toInt()
        val bottom = maxOf(startY, currentY).toInt()
        selectedRect = Rect(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw dark background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (isDrawing || selectedRect != null) {
            val left = minOf(startX, currentX)
            val top = minOf(startY, currentY)
            val right = maxOf(startX, currentX)
            val bottom = maxOf(startY, currentY)

            // Clear the hole
            canvas.drawRect(left, top, right, bottom, clearPaint)
            // Draw border
            canvas.drawRect(left, top, right, bottom, strokePaint)
        }
    }
}
