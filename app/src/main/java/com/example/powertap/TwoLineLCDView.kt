package com.drivool.iot.powertap

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class TwoLineLCDView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val line1 = LinearLayout(context)
    private val line2 = LinearLayout(context)

    init {
        orientation = VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.lcd_background)
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.lcd_green))

        setPadding(24, 16, 24, 16)

        line1.orientation = HORIZONTAL
        line2.orientation = HORIZONTAL

        addView(line1)
        addView(line2)
    }

    private fun setLine(linearLayout: LinearLayout, segments: List<LCDSegment>) {
        linearLayout.removeAllViews()
        segments.forEach { segment ->
            val container = LinearLayout(context)
            container.orientation = VERTICAL
            val containerParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, segment.weight)
            container.layoutParams = containerParams

            // Optional Label/Title
            if (!segment.label.isNullOrEmpty()) {
                val labelTv = TextView(context)
                labelTv.text = segment.label
                labelTv.setTextColor(ContextCompat.getColor(context, R.color.black))
                labelTv.alpha = 0.6f
                labelTv.textSize = 10f
                labelTv.gravity = when(segment.align) {
                    Align.LEFT -> Gravity.START
                    Align.CENTER -> Gravity.CENTER
                    Align.RIGHT -> Gravity.END
                }
                container.addView(labelTv)
            }

            val valueTv = TextView(context)
            valueTv.text = segment.text
            valueTv.setTextColor(ContextCompat.getColor(context, R.color.black))
            valueTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, segment.fontSize)
            valueTv.setTypeface(null, if (segment.bold) Typeface.BOLD else Typeface.NORMAL)
            valueTv.gravity = when(segment.align) {
                Align.LEFT -> Gravity.START
                Align.CENTER -> Gravity.CENTER
                Align.RIGHT -> Gravity.END
            }
            container.addView(valueTv)

            linearLayout.addView(container)
        }
    }

    fun setText(
        line1Segments: List<LCDSegment>,
        line2Segments: List<LCDSegment>
    ) {
        setLine(line1, line1Segments)
        setLine(line2, line2Segments)
    }

    fun clearAll() {
        line1.removeAllViews()
        line2.removeAllViews()
    }
}