package com.drivool.iot.powertap

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
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
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

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

    /**
     * Tapped while deactivated. People tap the disabled control rather than read
     * why it is disabled, so the host can use this to offer the fix instead of
     * swallowing the gesture.
     */
    var onBlockedTap: (() -> Unit)? = null

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
        handleRadius = h / 2f - HANDLE_INSET
        // Set initial position without animation
        handleX = restingHandleX()
    }

    private fun restingHandleX(): Float =
        if (currentState == "LEFT") handleRadius + HANDLE_INSET else width - handleRadius - HANDLE_INSET

    private fun updateHandlePosition(animated: Boolean) {
        if (width == 0) return
        val targetX = restingHandleX()

        
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
            handleRadius = height / 2f - HANDLE_INSET
            handleX = restingHandleX()
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

        drawLabel(canvas)

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

    /**
     * The handle covers one end of the track, so centring the label on the whole
     * width leaves it crammed against the handle with all the slack on the far
     * side. Centre it on the free part of the track instead, and shrink long
     * labels ("Connect to start charging") so they fit that space.
     */
    private fun drawLabel(canvas: Canvas) {
        textPaint.setShadowLayer(2f, 1f, 1f, Color.parseColor("#44000000"))

        // Handle at rest, not the live position: sizing off a moving handle
        // would make the label resize on every drag frame.
        val restingSpan = HANDLE_INSET + handleRadius * 2f
        val nearHandle = height * 0.10f
        // The rounded cap eats into the far end, so keep clear of the curve.
        val nearEdge = height * 0.30f

        val left: Float
        val right: Float
        if (currentState == "LEFT") {
            left = restingSpan + nearHandle
            right = width - nearEdge
        } else {
            left = nearEdge
            right = width - restingSpan - nearHandle
        }
        val available = right - left
        if (available <= 0f) return

        val preferredSize = height * 0.25f
        textPaint.textSize = preferredSize
        val measured = textPaint.measureText(sliderText)
        if (measured > available) {
            textPaint.textSize = max(height * 0.16f, preferredSize * available / measured)
        }

        val label = TextUtils.ellipsize(sliderText, textPaint, available, TextUtils.TruncateAt.END)
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(label, 0, label.length, (left + right) / 2f, textY, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) {
            // Consume the gesture so ACTION_UP arrives and we can offer the fix.
            // `locked` is left alone: that means our own command is in flight.
            if (event.action == MotionEvent.ACTION_UP) onBlockedTap?.invoke()
            return true
        }
        if (locked) return false
        
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
                    handleX = min(
                        width - handleRadius - HANDLE_INSET,
                        max(handleRadius + HANDLE_INSET, event.x),
                    )
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

    private companion object {
        /** Gap between the handle and the groove edge, in pixels. */
        const val HANDLE_INSET = 6f
    }
}
