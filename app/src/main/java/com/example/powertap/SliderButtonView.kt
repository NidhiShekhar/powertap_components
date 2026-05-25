package com.example.powertap

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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
    private var handleX = 0f

    private var dragging = false
    private var active = true
    private var sliderText = "Device is offline"
    private var showLoader = false
    private var currentState = "LEFT"
    var onSlideRight: (() -> Unit)? = null
    var onSlideLeft: (() -> Unit)? = null

    init {
        bgPaint.color = Color.parseColor("#15A615") // Initial Green
        handlePaint.color = Color.WHITE
        textPaint.color = Color.BLACK
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        handleRadius = height / 2f - 6f
        if (handleX == 0f) {
            handleX = handleRadius + 6f
        }
        val grooveRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        bgPaint.shader = null
        if (currentState == "RIGHT") {
            bgPaint.color = Color.parseColor("#F57C20") // Orange when online
        } else {
            bgPaint.color = Color.parseColor("#15A615") // Green initially/offline
        }
        
        bgPaint.setShadowLayer(8f, 0f, 4f, Color.parseColor("#33000000"))
        setLayerType(LAYER_TYPE_SOFTWARE, bgPaint)

        val corner = height / 2f
        canvas.drawRoundRect(grooveRect, corner, corner, bgPaint)

        textPaint.color = Color.WHITE // Better contrast on green/orange
        textPaint.textSize = height * 0.25f
        textPaint.isFakeBoldText = true

        canvas.drawText(sliderText, width / 2f + handleRadius/2, height / 2f - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)

        val handleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(12f, 0f, 6f, Color.parseColor("#44000000"))
        }
        canvas.drawCircle(handleX, height / 2f, handleRadius, handleShadowPaint)
        
        handlePaint.shader = null
        handlePaint.color = Color.WHITE
        canvas.drawCircle(handleX, height / 2f, handleRadius, handlePaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#EEEEEE")
        }
        canvas.drawCircle(handleX, height / 2f, handleRadius - 2f, ringPaint)

        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (currentState == "RIGHT") Color.parseColor("#F57C20") else Color.parseColor("#15A615")
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
        // if (!active) return false // Removed to allow sliding even if offline for demo
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x >= handleX - handleRadius * 1.5f && event.x <= handleX + handleRadius * 1.5f) {
                    dragging = true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    handleX = min(width - handleRadius - 6f, max(handleRadius + 6f, event.x))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    if (handleX > width * 0.7f) {
                        currentState = "RIGHT"
                        handleX = width - handleRadius - 6f
                        sliderText = "Device is online"
                        onSlideRight?.invoke()
                    } else {
                        currentState = "LEFT"
                        handleX = handleRadius + 6f
                        sliderText = "Device is offline"
                        onSlideLeft?.invoke()
                    }
                    invalidate()
                }
            }
        }
        return true
    }

    fun activate(flag: Boolean) {
        active = flag
        sliderText = if (active) "Slide to start" else "Device is offline"
        invalidate()
    }

    fun showProgress(message: String) {
        sliderText = message
        showLoader = true
        invalidate()
    }

    fun hideProgress() {
        showLoader = false
        sliderText = if (currentState == "LEFT") "Slide to start" else "Slide to stop"
        invalidate()
    }

    fun setState(toLeft: Boolean) {
        if (toLeft) {
            currentState = "LEFT"
            handleX = handleRadius + 6f
        } else {
            currentState = "RIGHT"
            handleX = width - handleRadius - 6f
        }
        invalidate()
    }

    fun setText(text: String) {
        sliderText = text
        invalidate()
    }
}