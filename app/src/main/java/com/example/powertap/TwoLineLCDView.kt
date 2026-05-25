package com.example.powertap

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
        // Note: R.drawable.lcd_background needs to be created
        background = ContextCompat.getDrawable(context, R.drawable.lcd_background)
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.lcd_green))

        setPadding(32, 32, 32, 32)

        line1.orientation = HORIZONTAL
        line2.orientation = HORIZONTAL

        addView(line1)
        addView(line2)
    }

    fun setLine(linearLayout: LinearLayout, segments: List<LCDSegment>) {
        linearLayout.removeAllViews()
        segments.forEach { segment ->
            val tv = TextView(context)
            tv.setPadding(20, 0, 20, 0)
            tv.text = segment.text
            tv.setTextColor(ContextCompat.getColor(context, R.color.black))
            tv.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                segment.fontSize
            )
            tv.setTypeface(null, Typeface.BOLD)
            if (segment.bold) {
                tv.setTypeface(null, Typeface.BOLD)
            }

            tv.gravity = when(segment.align) {
                Align.LEFT -> Gravity.START
                Align.CENTER -> Gravity.CENTER
                Align.RIGHT -> Gravity.END
            }

            val params = LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                segment.weight
            )
            tv.layoutParams = params
            linearLayout.addView(tv)
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