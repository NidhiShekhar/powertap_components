package com.drivool.iot.powertap

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

class SliderButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var handleRadius = 0f
    private var handleX = -1f

    private var dragging = false
    private var active = true
    private var locked = false
    val isActive: Boolean get() = active
    val isLocked: Boolean get() = locked

    private var sliderText = "Device is offline"
    private var currentState = "LEFT" // "LEFT" or "RIGHT"
    
    var onSlideRight: (() -> Unit)? = null
    var onSlideLeft: (() -> Unit)? = null

    private var animator: ValueAnimator? = null

    init {
        bgPaint.color = Color.parseColor("#15A615")
        handlePaint.color = Color.WHITE
        textPaint.color = Color.WHITE
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        handleRadius = h / 2f - 6f
        // Set initial position without animation
        handleX = if (currentState == "LEFT") handleRadius + 6f else width - handleRadius - 6f
    }

    private fun updateHandlePosition(animated: Boolean) {
        if (width == 0) return
        val targetX = if (currentState == "LEFT") handleRadius + 6f else width - handleRadius - 6f
        
        if (animated) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(handleX, targetX).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    handleX = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            handleX = targetX
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height == 0 || width == 0) return

        // Safety check for handleX
        if (handleX == -1f) {
            handleRadius = height / 2f - 6f
            handleX = if (currentState == "LEFT") handleRadius + 6f else width - handleRadius - 6f
        }

        val grooveRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        // Background color logic
        if (!active) {
            bgPaint.color = Color.GRAY
        } else if (locked) {
            bgPaint.color = Color.parseColor("#888888") 
        } else if (currentState == "RIGHT") {
            bgPaint.color = Color.parseColor("#F57C20") // Orange
        } else {
            bgPaint.color = Color.parseColor("#15A615") // Green
        }
        
        val corner = height / 2f
        canvas.drawRoundRect(grooveRect, corner, corner, bgPaint)

        // Draw text ALWAYS CENTERED in the middle of the button
        textPaint.textSize = height * 0.25f
        // Use a slight shadow for text readability
        textPaint.setShadowLayer(2f, 1f, 1f, Color.parseColor("#44000000"))
        
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(sliderText, width / 2f, textY, textPaint)

        // Handle
        handlePaint.color = if (active && !locked) Color.WHITE else Color.parseColor("#DDDDDD")
        canvas.drawCircle(handleX, height / 2f, handleRadius, handlePaint)

        // Arrow
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (!active || locked) Color.GRAY 
                    else if (currentState == "RIGHT") Color.parseColor("#F57C20") 
                    else Color.parseColor("#15A615")
            strokeWidth = 6f
            style = Paint.Style.FILL_AND_STROKE
            strokeCap = Paint.Cap.ROUND
        }

        val cx = handleX
        val cy = height / 2f
        val arrowSize = handleRadius * 0.3f

        canvas.save()
        if (currentState == "RIGHT") {
            canvas.rotate(180f, cx, cy)
        }
        
        val path = Path()
        path.moveTo(cx - arrowSize * 0.5f, cy - arrowSize)
        path.lineTo(cx + arrowSize * 0.5f, cy)
        path.lineTo(cx - arrowSize * 0.5f, cy + arrowSize)
        path.close()
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active || locked) return false
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Larger touch target for the handle
                if (event.x >= handleX - handleRadius * 2.5f && event.x <= handleX + handleRadius * 2.5f) {
                    dragging = true
                    animator?.cancel()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    handleX = min(width - handleRadius - 6f, max(handleRadius + 6f, event.x))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    val oldState = currentState
                    // Threshold for switching state
                    if (handleX > width * 0.5f) {
                        currentState = "RIGHT"
                    } else {
                        currentState = "LEFT"
                    }
                    
                    updateHandlePosition(true)
                    
                    if (currentState != oldState) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        if (currentState == "RIGHT") {
                            onSlideRight?.invoke()
                        } else {
                            onSlideLeft?.invoke()
                        }
                    }
                }
            }
        }
        return true
    }

    fun activate(flag: Boolean) {
        if (active != flag) {
            active = flag
            invalidate()
        }
    }

    fun showProgress(message: String) {
        sliderText = message
        locked = true
        invalidate()
    }

    fun hideProgress() {
        locked = false
        invalidate()
    }

    fun setState(toLeft: Boolean, animated: Boolean = true) {
        val newState = if (toLeft) "LEFT" else "RIGHT"
        if (newState != currentState) {
            currentState = newState
            updateHandlePosition(animated)
        } else if (!animated) {
            updateHandlePosition(false)
        }
    }

    fun setText(text: String) {
        if (sliderText != text) {
            sliderText = text
            invalidate()
        }
    }
}
